package com.fslab.jbd2.model;

public enum TxnStatus {
    /** Descriptor, all data blocks and a checksum-valid commit block present. */
    COMPLETE_VALID,
    /** Full shape present but the commit-block checksum does not guard it. */
    COMMIT_CKSUM_FAIL,
    /** Descriptor seen but the linear scan stopped before a commit arrived. */
    INCOMPLETE_HALF_TXN,
    /** A data block of a tagged run is missing/non-linear. */
    TRUNCATED_DATA_RUN
}
