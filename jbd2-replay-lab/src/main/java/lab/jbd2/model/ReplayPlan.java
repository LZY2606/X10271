package lab.jbd2.model;

import java.util.List;

/** Derived replay plan for one branch (plus machine-readable adjudication). */
public record ReplayPlan(
        String planId,
        String branch,
        boolean replayable,
        String replayGate,
        long crashPointPhysical,
        List<ReplayAction> actions,
        List<RuleEvent> events,
        String uncertaintyBoundary,
        List<String> candidateBranches) {
}
