package jbd2lab.domain;

import java.util.List;

/**
 * Parse result for one raw block. Tags/revoked/payload are populated per type.
 */
public record ParsedBlock(
        BlockRef ref,
        long sequence,
        BlockStatus status,
        List<JournalFormat.Tag> tags,
        List<Long> revoked,
        int storedChecksum,
        byte[] payload,
        String note) {

    public static ParsedBlock of(BlockRef ref, long sequence, BlockStatus status, String note) {
        return new ParsedBlock(ref, sequence, status, List.of(), List.of(), 0, new byte[0], note);
    }
}
