package jrnl.domain;

import java.util.List;

/**
 * Derived replay plan for a crash point. When the log does not admit a unique
 * legal replay (e.g. boundary after a checksum failure), every legal candidate
 * is retained - ambiguity is never collapsed to a single answer.
 */
public record ReplayPlan(
        String planId,
        CrashPoint crashPoint,
        String certainty,
        List<String> candidateLabels,
        List<ReplayStep> steps,
        List<RevokeHit> revokeHits,
        List<String> diagnostics) {

    public boolean unique() {
        return "UNIQUE".equals(certainty);
    }
}
