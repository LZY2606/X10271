package jrnl.derive;

import jrnl.domain.ReplayPlan;
import jrnl.domain.ReplayStep;

import java.util.List;
import java.util.Map;

/**
 * Planner output: the canonical plan plus every legal candidate's step list.
 * Ambiguity after a checksum failure is preserved here rather than collapsed.
 */
public record PlanResult(ReplayPlan plan, Map<String, List<ReplayStep>> candidateSteps) {

    public List<ReplayStep> stepsOf(String candidateLabel) {
        return candidateSteps.getOrDefault(candidateLabel, plan.steps());
    }
}
