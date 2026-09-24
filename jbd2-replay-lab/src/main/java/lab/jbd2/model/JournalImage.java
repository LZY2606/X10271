package lab.jbd2.model;

import java.util.List;

/**
 * Source snapshot of one imported ext4/JBD2 journal. Every quantity carries its unit /
 * coordinate basis; invalid values are retained as {@link Quantity} diagnostics rather
 * than dropped.
 */
public record JournalImage(
        String label,
        Quantity blockSize,
        long ringStart,
        List<Quantity> expectedFirstSequence,
        Quantity maxJournalBlocks,
        long crashTailBlock,
        ChecksumVersion checksumVersion,
        List<RawBlock> blocks,
        List<Quantity> diagnostics) {

    /** Convenience for fixtures with fully valid parameters. */
    public static JournalImage valid(String label, long blockSize, long ringStart, long firstSeq,
                                    long maxJournalBlocks, long crashTailBlock,
                                    ChecksumVersion checksumVersion, List<RawBlock> blocks) {
        return new JournalImage(label,
                Quantity.of(blockSize, "B", "bytes per journal block (superblock s_blocksize)"),
                ringStart,
                List.of(Quantity.of(firstSeq, "seq", "journal sequence id, wraps at 2^32")),
                Quantity.of(maxJournalBlocks, "block", "physical ring capacity incl. superblock"),
                crashTailBlock, checksumVersion, blocks, List.of());
    }

    public RawBlock blockAt(long physical) {
        for (RawBlock b : blocks) {
            if (b.physical() == physical) {
                return b;
            }
        }
        return null;
    }

    public boolean validBlockSize() {
        return blockSize.convertible()
                && Long.bitCount(blockSize.value()) == 1
                && blockSize.value() >= 1024
                && blockSize.value() <= 65536;
    }
}
