package jrnl.derive;

import jrnl.domain.*;

import java.util.Map;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Derives the ordered replay plan for a crash point.
 *
 * Invariants:
 *  - ONLY transactions with a complete, checksum-OK commit are replayed;
 *  - revokes take effect by sequence RANGE: a write is masked only when its
 *    sequence is strictly older (on the 32-bit ring) than a surviving revoke
 *    for the same block. Same-sequence writes survive; later rewrites after a
 *    revoke survive ("revoke-then-rewrite");
 *  - ring wrap uses {@link Seq} arithmetic, never integer sort;
 *  - after a checksum failure the boundary stays uncertain: every legal
 *    candidate (exclude / include the suspect transaction) is retained.
 */
public final class ReplayPlanner {

    public static final String RULES_VERSION = "rules-v1.0.0";

    private ReplayPlanner() {
    }

    public static PlanResult plan(Snapshot snapshot, ScanResult scan, CrashPoint crashPoint) {
        List<String> diags = new ArrayList<>();
        if (!snapshot.blockSize().valid()) {
            ReplayPlan blocked = new ReplayPlan(planId(snapshot.caseId(), crashPoint, "BLOCKED"),
                    crashPoint, "BLOCKED", List.of("BLOCKED_INVALID_BLOCK_SIZE"),
                    List.of(), List.of(),
                    List.of("cannot plan replay: " + snapshot.blockSize().diagnostic()
                            + " (raw representation retained at source)"));
            return new PlanResult(blocked, Map.of("BLOCKED_INVALID_BLOCK_SIZE", List.of()));
        }

        Map<Integer, RawBlock> byIndex = new HashMap<>();
        snapshot.journalBlocks().forEach(rb -> byIndex.put(rb.imageIndex(), rb));

        // Transactions physically visible at the crash point (members up to bound).
        List<TxnGroup> visibleTxns = new ArrayList<>();
        TxnGroup badBoundary = null;
        for (TxnGroup t : scan.txns()) {
            boolean membersVisible = t.memberPos().stream().allMatch(crashPoint::contains);
            if (!membersVisible) {
                diags.add("seq " + t.sequence().hex() + " is past the crash point — ignored");
                continue;
            }
            if (t.status().equals("COMMIT_CHECKSUM_BAD")) {
                badBoundary = t;
            }
            visibleTxns.add(t);
        }

        // Surviving revoke table (complete revoke transactions only).
        List<Revoke> revokeTable = new ArrayList<>();
        for (TxnGroup t : visibleTxns) {
            if (!t.complete()) {
                continue;
            }
            if (t.revokePos() != null && !t.revokes().isEmpty()) {
                for (Long fsBlock : t.revokes()) {
                    revokeTable.add(new Revoke(fsBlock, t.sequence(), t.revokePos()));
                }
            }
        }

        // Ordered candidate writes in physical ring order.
        List<Write> ordered = new ArrayList<>();
        for (TxnGroup t : visibleTxns) {
            if (!t.status().equals("COMPLETE") && !t.status().equals("COMMIT_CHECKSUM_BAD")) {
                diags.add("seq " + t.sequence().hex() + " not replayable: " + t.status());
                continue;
            }
            for (TxnGroup.DataWrite w : t.writes()) {
                RawBlock rb = byIndex.get(w.dataPos().imageIndex());
                ordered.add(new Write(t, w, rb == null ? "" : rb.hexBytes()));
            }
        }

        List<RevokeHit> hits = evaluateRevokes(ordered, revokeTable);

        if (badBoundary == null) {
            List<ReplayStep> steps = materialize(ordered, hits, Set.of(), null);
            diags.add("exactly one legal replay: every applied transaction has a complete commit");
            ReplayPlan plan = new ReplayPlan(planId(snapshot.caseId(), crashPoint, "UNIQUE"),
                    crashPoint, "UNIQUE", List.of("UNIQUE"), steps, hits, diags);
            return new PlanResult(plan, Map.of("UNIQUE", steps));
        }

        Seq badSeq = badBoundary.sequence();
        List<ReplayStep> exclude = materialize(ordered, hits, Set.of(badSeq), badSeq);
        List<ReplayStep> include = materialize(ordered, hits, Set.of(), badSeq);
        diags.add("commit checksum failure for seq " + badSeq.hex()
                + " — boundary cannot be decided uniquely; both legal candidates retained");
        String excludeLabel = "CANDIDATE_EXCLUDE_seq" + badSeq.hex();
        String includeLabel = "CANDIDATE_INCLUDE_seq" + badSeq.hex();
        ReplayPlan plan = new ReplayPlan(planId(snapshot.caseId(), crashPoint, "EXCLUDE"),
                crashPoint, "AMBIGUOUS", List.of(excludeLabel, includeLabel), exclude, hits, diags);
        return new PlanResult(plan, Map.of(excludeLabel, exclude, includeLabel, include));
    }

    private static List<RevokeHit> evaluateRevokes(List<Write> ordered, List<Revoke> revokeTable) {
        List<RevokeHit> hits = new ArrayList<>();
        for (Write w : ordered) {
            Revoke newest = null;
            for (Revoke r : revokeTable) {
                if (r.fsBlock == w.write.fsBlock()
                        && w.txn.sequence().olderThan(r.seq)) {
                    if (newest == null || newest.seq.before(r.seq)) {
                        newest = r;
                    }
                }
            }
            boolean masked = newest != null;
            hits.add(new RevokeHit(
                    w.write.fsBlock(),
                    w.txn.sequence(),
                    masked ? newest.seq : null,
                    masked ? newest.pos : null,
                    w.write.dataPos(),
                    masked,
                    masked
                            ? "write seq " + w.txn.sequence().hex() + " is older than revoke seq "
                                    + newest.seq.hex() + " (ring-range rule, strictly-older)"
                            : "no newer revoke for this fs block; rewrite survives"));
        }
        return hits;
    }

    private static List<ReplayStep> materialize(List<Write> ordered, List<RevokeHit> hits,
                                                Set<Seq> excluded, Seq badSeq) {
        Map<Integer, Boolean> maskedByIndex = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            maskedByIndex.put(i, hits.get(i).masked());
        }
        // Last unmasked, non-excluded write per fs block wins (duplicate blocks).
        Map<Long, Integer> winner = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Write w = ordered.get(i);
            if (excluded.contains(w.txn.sequence()) || maskedByIndex.get(i)) {
                continue;
            }
            winner.put(w.write.fsBlock(), i);
        }
        List<ReplayStep> steps = new ArrayList<>();
        int order = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Write w = ordered.get(i);
            long fs = w.write.fsBlock();
            String status;
            String reason;
            if (excluded.contains(w.txn.sequence())) {
                status = "EXCLUDED_SUSPECT";
                reason = "candidate excludes seq " + w.txn.sequence().hex()
                        + " due to commit checksum failure";
            } else if (maskedByIndex.get(i)) {
                status = "REVOKED";
                reason = hits.get(i).basis();
            } else if (winner.get(fs) != i) {
                status = "SUPERSEDED";
                reason = "same fs block rewritten later in replay coverage order; last write wins";
            } else {
                status = "APPLIED";
                reason = "complete commit, fs block " + fs + " is the final value";
            }
            steps.add(new ReplayStep(++order, w.txn.sequence(), fs, w.write.dataPos(),
                    w.dataHex, status, reason));
        }
        return steps;
    }

    /** Deterministic content identity: replaying twice yields the same plan id. */
    public static String planId(String caseId, CrashPoint cp, String variant) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String basis = RULES_VERSION + "|" + caseId + "|"
                + cp.stopAfterImageIndex() + "|" + cp.rawRepr() + "|" + variant;
        byte[] d = md.digest(basis.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", d[i]));
        }
        return "plan-" + sb;
    }

    private record Revoke(long fsBlock, Seq seq, RingPos pos) {
    }

    private record Write(TxnGroup txn, TxnGroup.DataWrite write, String dataHex) {
    }
}
