package lab.jbd2.model;

public enum TxnStatus {
    /** Descriptor + commit present and every checksum valid. */
    COMPLETE,
    /** Descriptor seen but no commit before the crash tail (half transaction). */
    INCOMPLETE,
    /** Descriptor or commit checksum failed: replay boundary is uncertain. */
    CORRUPT
}
