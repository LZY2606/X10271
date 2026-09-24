package jbd2lab.domain;

public enum TransactionStatus {
    /** Descriptor (+ data) with a matching commit block: replayable. */
    COMMITTED,
    /** Head blocks present but commit block missing/torn: half transaction, never replayed. */
    INCOMPLETE,
    /** Commit block present but its CRC32C does not verify: boundary uncertainty, candidates retained. */
    COMMIT_CKSUM_FAIL,
    /** Descriptor/revoke body checksum failed: body integrity unknown. */
    BODY_CKSUM_FAIL
}
