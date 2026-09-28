package org.fslab.jbd2.model;

/** JBD2 journal block flavours recognised by the scanner. */
public enum BlockType {
    SUPERBLOCK,
    DESCRIPTOR,
    COMMIT,
    REVOKE,
    DATA,
    UNKNOWN
}
