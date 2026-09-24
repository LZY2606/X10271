package com.fslab.jbd2.model;

import java.util.List;

/**
 * A replay plan derived from a scan report, a chosen boundary candidate and an
 * expected crash point. rootHash covers ordered derived blocks, so replaying the
 * same inputs twice is provably idempotent.
 */
public record ReplayPlan(
        String planId,
        long crashAfterRing,
        String boundaryCandidateId,
        List<Long> replayedSequences,
        List<Long> excludedSequences,
        List<ReplayStep> steps,
        List<DerivedBlock> derivedBlocks,
        String rootHash,
        boolean idempotent,
        List<RuleEvent> ruleEvents,
        List<Diagnostic> diagnostics) {

    public static String id(String scanId, String candidateId, long crashAfterRing) {
        return "plan:" + scanId + ":" + candidateId + ":crash" + crashAfterRing;
    }
}
