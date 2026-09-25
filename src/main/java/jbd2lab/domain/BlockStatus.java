package jbd2lab.domain;

/** Parse state of a single journal block. */
public enum BlockStatus {
    DESCRIPTOR,
    COMMIT,
    REVOKE,
    DATA,
    EMPTY,
    BAD_MAGIC,
    UNKNOWN_TYPE,
    UNEXPECTED
}
