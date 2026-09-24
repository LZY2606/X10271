package lab.jbd2.model;

public enum ActionDecision {
    APPLY,
    DUP_SHADOWED,
    SUPPRESSED_BY_REVOKE,
    SKIPPED_INCOMPLETE,
    SKIPPED_UNCERTAIN,
    AFTER_CRASH_POINT
}
