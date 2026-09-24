package jbd2lab.domain;

/**
 * One physical journal block with its original bytes preserved plus parsed header view.
 * Coordinates: {@code ringIndex} is slot relative to journal start; {@code absBlock} is
 * the offset inside the journal device image in blocks (unit: blocks).
 */
public record JournalBlock(
        int ringIndex,
        int absBlock,
        int blockType,
        long headerSequence,
        byte[] raw,
        long storedChecksum,
        long computedChecksum,
        boolean checksumMatches,
        String parseError
) {
    public String type() {
        return Jbd2Format.typeName(blockType);
    }

    public String rawSha() {
        return Bytes.sha256Hex(raw);
    }
}
