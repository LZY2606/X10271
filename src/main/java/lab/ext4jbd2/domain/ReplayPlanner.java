package lab.ext4jbd2.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a scan result into a replay plan.
 *
 * Rules (RuleBook):
 *  - only COMMITTED transactions enter the plan
 *  - revoke committed in transaction R suppresses writes of the same fs block
 *    from transactions whose sequence is in [firstSequence, R] (mod 2^32),
 *    i.e. SeqNum.geq(R, S); a rewrite after the revoke (S later than R) survives
 *  - surviving duplicate writes to one fs block are kept in coverage order;
 *    the last one is effective
 *  - checksum-break candidates produce parallel plans; none is silently dropped
 */
public final class ReplayPlanner {
    private ReplayPlanner() {}

    public static ReplayPlan plan(ScanResult scan, byte[][] journal, RuleBook rules,
                                  Map<String, String> selection) {
        ReplayPlan p = new ReplayPlan();
        p.ruleVersion = rules.version;
        if (selection != null) p.selection.putAll(selection);

        List<ScanResult.Txn> mainline = committed(scan.txns);
        Map<String, List<ScanResult.Txn>> candidateTxns = new LinkedHashMap<>();
        for (ScanResult.Anomaly a : scan.anomalies) {
            if (a.candidates.isEmpty()) continue;
            p.uncertainties.put(a.id, a.candidates.stream().map(c -> c.id).toList());
            candidateTxns.put(a.id + ":stop-boundary", mainline);
            if (scan.continuation != null) {
                List<ScanResult.Txn> merged = new ArrayList<>(mainline);
                merged.addAll(committed(scan.continuation.txns));
                candidateTxns.put(a.id + ":torn-continue", merged);
            }
        }

        boolean provisional = false;
        List<ScanResult.Txn> effectiveTxns = new ArrayList<>(mainline);
        for (ScanResult.Anomaly a : scan.anomalies) {
            if (a.candidates.isEmpty()) continue;
            String chosen = selection.get(a.id);
            if (chosen == null) {
                provisional = true;
                chosen = "stop-boundary";
                p.notes.add("anomaly " + a.id
                        + " has no confirmed candidate; plan is provisional on 'stop-boundary'");
            }
            if ("torn-continue".equals(chosen) && scan.continuation != null) {
                effectiveTxns.addAll(committed(scan.continuation.txns));
            }
        }
        p.provisional = provisional;

        List<ReplayPlan.Write> effectiveWrites = buildWrites(rules, effectiveTxns);
        p.writes.addAll(effectiveWrites);
        p.revokeHits.addAll(revokeHits(effectiveWrites));
        for (var e : candidateTxns.entrySet()) {
            p.alternatives.put(e.getKey(), buildWrites(rules, e.getValue()));
        }
        return p;
    }

    private static List<ScanResult.Txn> committed(List<ScanResult.Txn> txns) {
        List<ScanResult.Txn> out = new ArrayList<>();
        for (ScanResult.Txn t : txns) if ("COMMITTED".equals(t.status)) out.add(t);
        return out;
    }

    private static List<ReplayPlan.Write> buildWrites(RuleBook rules, List<ScanResult.Txn> txns) {
        List<ScanResult.Txn> ordered = new ArrayList<>(txns);
        if ("plain-seq-sort".equals(rules.replayOrder)) {
            ordered.sort(Comparator.comparingLong(t -> t.sequence));
        }
        // revoke table: fs block -> latest (mod-2^32) committing revoke sequence
        Map<Long, ScanResult.Txn> revokes = new LinkedHashMap<>();
        for (ScanResult.Txn t : ordered) {
            if (t.revokeBlocks == null) continue;
            for (Long blk : t.revokeBlocks) {
                ScanResult.Txn prev = revokes.get(blk);
                if (prev == null || SeqNum.gt(t.sequence, prev.sequence)) revokes.put(blk, t);
            }
        }
        List<ReplayPlan.Write> writes = new ArrayList<>();
        for (ScanResult.Txn t : ordered) {
            for (int i = 0; i < t.fsBlocks.size() && i < t.dataCoords.size(); i++) {
                long fs = t.fsBlocks.get(i);
                ReplayPlan.Write w = new ReplayPlan.Write(fs, t.dataCoords.get(i), t.sequence);
                ScanResult.Txn rev = rules.revokeBySequenceRange ? revokes.get(fs) : null;
                if (rev != null && SeqNum.geq(rev.sequence, t.sequence)) {
                    w.status = "revoked";
                    w.revokeTxnSeq = rev.sequence;
                    w.revokeCoord = rev.revokeCoord;
                }
                writes.add(w);
            }
        }
        // coverage + duplicate resolution
        Map<Long, List<Integer>> coverage = new LinkedHashMap<>();
        for (int i = 0; i < writes.size(); i++) {
            coverage.computeIfAbsent(writes.get(i).fsBlock, k -> new ArrayList<>()).add(i);
        }
        for (var e : coverage.entrySet()) {
            List<Integer> idxs = e.getValue();
            int lastEffective = -1;
            for (int idx : idxs) {
                if ("revoked".equals(writes.get(idx).status)) continue;
                lastEffective = idx;
            }
            for (int idx : idxs) {
                ReplayPlan.Write w = writes.get(idx);
                if ("revoked".equals(w.status)) continue;
                if (idx != lastEffective) w.status = "superseded";
            }
        }
        return writes;
    }

    /** Applies the effective writes to a snapshot; pure function. */
    public static Map<Long, byte[]> apply(ReplayPlan plan, byte[][] journal,
                                          Map<Long, byte[]> snapshot) {
        Map<Long, byte[]> image = new LinkedHashMap<>(snapshot);
        for (ReplayPlan.Write w : plan.writes) {
            if ("effective".equals(w.status)) {
                image.put(w.fsBlock, journal[w.dataCoord]);
            }
        }
        return image;
    }

    /** Applies an alternative candidate's writes (used by the branch-compare view). */
    public static Map<Long, byte[]> applyAlternative(String candidateKey, ReplayPlan plan,
                                                     byte[][] journal,
                                                     Map<Long, byte[]> snapshot) {
        List<ReplayPlan.Write> writes = plan.alternatives.get(candidateKey);
        Map<Long, byte[]> image = new LinkedHashMap<>(snapshot);
        if (writes != null) {
            Map<Long, Integer> last = new LinkedHashMap<>();
            for (int i = 0; i < writes.size(); i++) {
                ReplayPlan.Write w = writes.get(i);
                if (!"revoked".equals(w.status)) last.put(w.fsBlock, i);
            }
            for (int i = 0; i < writes.size(); i++) {
                ReplayPlan.Write w = writes.get(i);
                if ("revoked".equals(w.status)) continue;
                if (last.get(w.fsBlock) == i) image.put(w.fsBlock, journal[w.dataCoord]);
            }
        }
        return image;
    }

    public static List<ReplayPlan.RevokeHit> revokeHits(List<ReplayPlan.Write> writes) {
        List<ReplayPlan.RevokeHit> hits = new ArrayList<>();
        for (ReplayPlan.Write w : writes) {
            if ("revoked".equals(w.status)) {
                hits.add(new ReplayPlan.RevokeHit(w.fsBlock, w.txnSeq, w.dataCoord,
                        w.revokeTxnSeq, w.revokeCoord));
            }
        }
        return hits;
    }
}
