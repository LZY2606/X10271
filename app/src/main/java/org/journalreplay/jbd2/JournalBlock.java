package org.journalreplay.jbd2;

import java.util.List;

/**
 * One parsed journal block. All coordinates keep their basis explicit:
 * {@code ringIndex} is relative to the journal log start, {@code absBlock} is
 * a filesystem block number, {@code sequence} is the raw 32-bit journal
 * sequence (ring arithmetic, see {@link SeqCompare}).
 */
public record JournalBlock(
        long ringIndex,
        long absBlock,
        long sequence,
        BlockType type,
        List<Long> fsBlocks,   // descriptor tag targets, in tag order
        List<Long> revoked,    // revoke record targets
        byte[] data,           // payload for DATA blocks (blockSize bytes)
        ChecksumStatus checksum,
        String rawHeaderHex) {

    public enum ChecksumStatus { OK, FAILED, UNKNOWN, NOT_APPLICABLE }

    public String stableId() {
        return "jblk-" + ringIndex;
    }
}
