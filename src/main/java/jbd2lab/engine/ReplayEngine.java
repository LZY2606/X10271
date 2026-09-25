package jbd2lab.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jbd2lab.domain.BlockRef;
import jbd2lab.domain.JournalFormat;
import jbd2lab.domain.JournalImage;
import jbd2lab.domain.RawBlock;
import jbd2lab.domain.ScanResult;
import jbd2lab.domain.SeqNum;
import jbd2lab.domain.TransactionGroup;

/**
 * Deterministic replay planner.
 *
 * <p>Semantic guarantees (see case pack event rules):
 * <ul>
 *   <li>Only fully committed transactions are replayable; a half transaction never is.</li>
 *   <li>Revoke acts by sequence range: a revoke at R cancels the live write at W
 *       when W is strictly older on the uint32 ring; a rewrite newer than R is
 *       replayed. Wrap ordering uses {@link SeqNum}, never plain int sorting.</li>
 *   <li>After the first checksum-failure boundary nothing is auto-merged. Every
 *       legal continuation (each ordered cut of the remaining committed groups)
 *       is preserved as an explicit candidate branch.</li>
 *   <li>The crash point truncates by journal ring offset; a transaction whose
 *       commit lies beyond the crash never existed at replay time.</li>
 * </ul>
 */
public final class ReplayEngine {

    public ReplayResult plan(ScanResult scan, JournalImage image, long crashPointOffset) {
        List<String> errors = new ArrayList<>();
        if (!scan.geom().blockSizeValid()) {
            return ReplayResult.refused(
                    List.of("REFUSED_INVALID_BLOCK_SIZE: " + scan.geom().blockSize()));
        }
        if (!scan.diagnostics().isEmpty()) {
            errors.addAll(scan.diagnostics());
        }

        List<TransactionGroup> replayableByCrash = new ArrayList<>();
        boolean crashTruncated = false;
        for (TransactionGroup g : scan.groups()) {
            if (!g.replayable()) {
                continue;
            }
            if (crashPointOffset >= 0 && g.commit() != null
                    && g.commit().ringOffset() > crashPointOffset) {
                crashTruncated = true;
                continue;
            }
            replayableByCrash.add(g);
        }

        int certainCount = 0;
        for (TransactionGroup g : replayableByCrash) {
            if (scan.boundaryOffset() >= 0 && g.descriptor().ringOffset() >= scan.boundaryOffset()) {
                break;
            }
            certainCount++;
        }

        List<TransactionGroup> certain = replayableByCrash.subList(0, certainCount);
        List<TransactionGroup> uncertain = replayableByCrash.subList(certainCount,
                replayableByCrash.size());

        ReplayPlan base = buildPlan("plan:cut=0", false, crashTruncated, crashPointOffset,
                certain, image,
                "REPLAY_RULE:committed_only+revoke_seq_range+ring_order");

        List<ReplayPlan> candidates = new ArrayList<>();
        if (!uncertain.isEmpty()) {
            for (int k = 1; k <= uncertain.size(); k++) {
                List<TransactionGroup> extend = new ArrayList<>(certain);
                extend.addAll(uncertain.subList(0, k));
                candidates.add(buildPlan("plan:cut=" + k + ":seq="
                        + uncertain.get(k - 1).sequence(), true, crashTruncated,
                        crashPointOffset, extend, image,
                        "UNCERTAIN_BOUNDARY_AFTER_CHECKSUM_FAILURE: legal continuation cut " + k
                                + " of " + uncertain.size()));
            }
        }
        return new ReplayResult(new ReplayPlan(base.planId(), false, crashTruncated,
                crashPointOffset, base.writes(), base.revokes(), base.revokeHits(),
                base.derivedBlocks(), List.copyOf(uncertain), List.copyOf(candidates),
                base.basis()), errors, false);
    }

    private ReplayPlan buildPlan(String planId, boolean uncertain, boolean crashTruncated,
                                 long crashPoint, List<TransactionGroup> groups,
                                 JournalImage image, String basis) {
        List<WriteEvent> writes = new ArrayList<>();
        List<RevokeEvent> revokes = new ArrayList<>();
        List<RevokeHit> hits = new ArrayList<>();
        int order = 0;

        Map<Long, WriteEvent> live = new LinkedHashMap<>();
        Map<Long, List<WriteEvent>> history = new LinkedHashMap<>();

        for (TransactionGroup g : groups) {
            List<Long> tagOrder = new ArrayList<>();
            g.covers().forEach((fs, refs) -> {
                for (BlockRef r : refs) {
                    tagOrder.add(fs);
                }
            });
            int dataIdx = 0;
            for (BlockRef m : g.members()) {
                if (g.revokes().contains(m)) {
                    RawBlock rb = image.at(m.physical());
                    for (long fs : JournalFormat.parseRevoked(rb.bytes())) {
                        RevokeEvent re = new RevokeEvent(g.sequence(), fs, m, order++);
                        revokes.add(re);
                        WriteEvent old = live.get(fs);
                        if (old != null && SeqNum.after(g.sequence(), old.sequence())) {
                            hits.add(new RevokeHit(re, old,
                                    "REVOKE_RANGE:seq " + g.sequence() + " after write seq "
                                            + old.sequence() + " on uint32 ring => suppress"));
                            live.remove(fs);
                        }
                    }
                } else if (g.dataBlocks().contains(m) && dataIdx < tagOrder.size()) {
                    long fs = tagOrder.get(dataIdx++);
                    RawBlock rb = image.at(m.physical());
                    byte[] payload = payloadOf(rb);
                    WriteEvent we = new WriteEvent(g.sequence(), fs, m, sha(rb), order++);
                    writes.add(we);
                    live.put(fs, we);
                    history.computeIfAbsent(fs, k -> new ArrayList<>()).add(we);
                }
            }
        }

        List<DerivedBlock> derived = new ArrayList<>();
        for (Map.Entry<Long, WriteEvent> e : live.entrySet()) {
            RawBlock rb = image.at(e.getValue().journalRef().physical());
            byte[] payload = payloadOf(rb);
            derived.add(new DerivedBlock(e.getKey(), e.getValue(), sha(rb), payload,
                    List.copyOf(history.getOrDefault(e.getKey(), List.of()))));
        }
        return new ReplayPlan(planId, uncertain, crashTruncated, crashPoint,
                List.copyOf(writes), List.copyOf(revokes), List.copyOf(hits),
                List.copyOf(derived), List.of(), List.of(), basis);
    }

    private static byte[] payloadOf(RawBlock rb) {
        byte[] b = rb.bytes();
        byte[] payload = new byte[b.length - JournalFormat.HEADER_SIZE];
        System.arraycopy(b, JournalFormat.HEADER_SIZE, payload, 0, payload.length);
        return payload;
    }

    private static String sha(RawBlock rb) {
        return rb.sha256();
    }

    /** Applying a derived state twice must produce identical bytes (replay idempotence). */
    public boolean idempotent(List<DerivedBlock> firstPass, List<DerivedBlock> secondPass) {
        if (firstPass.size() != secondPass.size()) {
            return false;
        }
        for (int i = 0; i < firstPass.size(); i++) {
            DerivedBlock a = firstPass.get(i);
            DerivedBlock b = secondPass.get(i);
            if (a.fsBlock() != b.fsBlock() || !java.util.Arrays.equals(a.bytes(), b.bytes())) {
                return false;
            }
        }
        return true;
    }
}
