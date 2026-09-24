package jbd2lab.domain;

import java.util.List;

/**
 * A candidate transaction group assembled from the ring. A transaction is identified by its
 * header sequence number (cyclic). Physical ring positions are preserved for every member.
 */
public record Transaction(
        long sequence,
        TransactionStatus status,
        int firstRingIndex,
        int lastRingIndex,
        Integer revokeAbsBlock,
        boolean revokeChecksumMatches,
        List<Long> revokedFsBlocks,
        Integer descriptorAbsBlock,
        boolean descriptorChecksumMatches,
        List<BlockRef> refs,
        Integer commitAbsBlock,
        boolean commitChecksumMatches,
        long commitStoredChecksum,
        long commitComputedChecksum
) {
    public boolean replayable() {
        return status == TransactionStatus.COMMITTED;
    }

    public boolean uncertain() {
        return status != TransactionStatus.COMMITTED && status != TransactionStatus.INCOMPLETE;
    }
}
