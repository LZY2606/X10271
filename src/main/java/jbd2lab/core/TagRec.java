package jbd2lab.core;

/** One descriptor tag mapping a journal data block to a filesystem block coordinate. */
public final class TagRec {
    public final int index;            // 0-based tag index inside the descriptor
    public final long fsBlock;         // target filesystem block (coordinate basis: fs_blocks)
    public final int flags;
    public final long expectedCrc;     // asserted crc32c of the data block (0 when TAG_IGNORE_CRC)
    public final boolean crcAsserted;
    public final int dataSlot;         // physical ring slot of the following data block
    public final int tagByteOffset;    // byte offset inside the descriptor block (origin)

    public TagRec(int index, long fsBlock, int flags, long expectedCrc,
                  boolean crcAsserted, int dataSlot, int tagByteOffset) {
        this.index = index;
        this.fsBlock = fsBlock;
        this.flags = flags;
        this.expectedCrc = expectedCrc;
        this.crcAsserted = crcAsserted;
        this.dataSlot = dataSlot;
        this.tagByteOffset = tagByteOffset;
    }

    public boolean escaped() { return (flags & JBD2.TAG_ESCAPE_FLAG) != 0; }
    public boolean last() { return (flags & JBD2.TAG_LAST_FLAG) != 0; }
}
