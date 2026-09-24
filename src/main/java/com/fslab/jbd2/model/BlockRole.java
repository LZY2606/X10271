package com.fslab.jbd2.model;

public enum BlockRole {
    JOURNAL_SUPERBLOCK,
    DESCRIPTOR,
    DATA,
    COMMIT,
    REVOKE,
    GAP,
    UNKNOWN
}
