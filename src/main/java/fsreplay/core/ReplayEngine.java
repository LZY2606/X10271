package fsreplay.core;

import fsreplay.core.Model.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a replay plan and derived block map from a scan.
 * Semantics:
 *  - only transactions with a complete, checksum-valid commit are replayed
 *  - a revoke record (seq Sr, block b) cancels writes to b from transactions with seq <= Sr
 *    (modular, wrap-aware); later rewrites (seq > Sr) stand
 *  - replay is idempotent: applying the plan to a snapshot twice == applying once
 *  - undecidable states are kept as candidates, never collapsed to a single answer
 */
public final class ReplayEngine {

    public static ReplayPlan plan(ScanResult scan) {
        List<PlanStep> steps = new ArrayList<>();
        List<String> diags = Model.newDiags();
        List<Model.ParsedBlock> revokeBlocks = new ArrayList<>();
        for (TxnGroup t : scan.transactions()) revokeBlocks.addAll(t.revokes());

        int order = 0;
        for (TxnGroup t : scan.transactions()) {
            if (!t.revokes().isEmpty()) continue; // revoke records are applied as range rules, not writes
            if (!t.committed()) {
                diags.add("txn seq " + Seq.hex(t.sequence()) + " skipped: no complete valid commit"
                        + (t.diagnostics().isEmpty() ? "" : " (" + String.join("; ", t.diagnostics()) + ")"));
                continue;
            }
            for (Model.ParsedBlock d : t.dataBlocks()) {
                long fsb = d.fsBlocks().get(0);
                String revokedBy = null;
                for (Model.ParsedBlock r : revokeBlocks) {
                    if (r.fsBlocks().contains(fsb) && !Seq.before(r.sequence(), t.sequence())) {
                        revokedBy = "revoked by revoke-record seq " + Seq.hex(r.sequence())
                                + " at journal block " + r.journalIndex();
                        break;
                    }
                }
                steps.add(new PlanStep(order++, fsb, d.journalIndex(), t.sequence(),
                        revokedBy != null, revokedBy));
            }
        }
        if (scan.checksumBreakIndex() != null) {
            diags.add("checksum break at journal block " + scan.checksumBreakIndex()
                    + ": blocks past the break were not scanned; boundary stays uncertain");
        }
        return new ReplayPlan(steps, diags);
    }

    /** Derive the block map at the scan's crash point. Last non-revoked write wins. */
    public static DerivedMap derive(ReplayPlan plan, Integer crashPoint) {
        Map<Long, MapEntry> map = new LinkedHashMap<>();
        List<String> diags = Model.newDiags();
        for (PlanStep s : plan.steps()) {
            if (s.revoked()) {
                diags.add("step " + s.order() + " fs-block " + s.fsBlock() + " dropped: " + s.reason());
                continue;
            }
            map.put(s.fsBlock(), new MapEntry(s.fsBlock(),
                    "jb#" + s.journalIndex() + "@seq=" + Seq.hex(s.txnSequence()),
                    s.journalIndex(), s.txnSequence(), false));
        }
        diags.addAll(plan.diagnostics());
        return new DerivedMap(List.copyOf(map.values()), crashPoint, diags);
    }

    /**
     * Apply a plan to a base snapshot (fsBlock -> bytes). Idempotent: apply(apply(s)) == apply(s)
     * because each step is an absolute write of journal block content, never a delta.
     */
    public static Map<Long, byte[]> apply(ReplayPlan plan, byte[] journalImage, int blockSize,
                                          Map<Long, byte[]> base) {
        Map<Long, byte[]> out = new LinkedHashMap<>(base);
        for (PlanStep s : plan.steps()) {
            if (s.revoked()) continue;
            byte[] blk = new byte[blockSize];
            System.arraycopy(journalImage, s.journalIndex() * blockSize, blk, 0, blockSize);
            out.put(s.fsBlock(), blk);
        }
        return out;
    }
}
