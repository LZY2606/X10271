package com.fslab.jbd2.model;

public enum ReplayOpKind {
    /** Bind a filesystem block to journal data during the forward pass. */
    BIND,
    /** Revoke-table entry recorded during the forward pass. */
    REVOKE_RECORD,
    /** Sweep: an older bound write is discarded because a later revoke covers it. */
    REVOKE_HIT,
    /** Sweep: a write is applied; later bindings for the same block overwrite. */
    APPLY,
    /** Sweep: an earlier duplicate binding is overwritten by a later one. */
    SUPERSEDED_DUPLICATE,
    /** Transaction excluded because it has no complete valid commit. */
    EXCLUDE_NO_COMMIT,
    /** Transaction excluded because it lies after the chosen crash point. */
    EXCLUDE_AFTER_CRASH
}
