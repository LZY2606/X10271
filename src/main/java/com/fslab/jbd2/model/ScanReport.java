package com.fslab.jbd2.model;

import java.util.List;

/** Full output of the journal scan for one snapshot version. */
public record ScanReport(
        String scanId,
        long blockCount,
        long scanStopRingPos,
        String scanStopReason,
        boolean sequenceWrapObserved,
        List<ScannedTransaction> transactions,
        List<JournalBlock> linearizedBlocks,
        List<DecisionCandidate> boundaryCandidates,
        List<RuleEvent> ruleEvents,
        List<Diagnostic> diagnostics) {

    public static String id(String snapshotId) {
        return "scan:" + snapshotId;
    }

    public ScannedTransaction txn(long seq) {
        for (ScannedTransaction t : transactions) if (t.sequence() == seq) return t;
        return null;
    }
}
