package org.jbd2lab.scan;

/** Checksum verdict for one journal block. */
public enum CheckVerdict {
    NOT_APPLICABLE,
    OK,
    BAD,
    NOT_PRESENT
}
