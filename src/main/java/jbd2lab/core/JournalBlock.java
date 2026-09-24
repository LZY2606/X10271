package jbd2lab.core;

import java.util.Arrays;

/**
 * One raw physical block of the journal ring. {@code slot} is the ring coordinate
 * (0-based offset in journal blocks from the ring start); the original bytes are kept
 * verbatim so any interpretation can be re-derived and audited.
 */
public final class JournalBlock {
    private final int slot;
    private final byte[] bytes;

    public JournalBlock(int slot, byte[] bytes) {
        this.slot = slot;
        this.bytes = bytes;
    }

    public int slot() { return slot; }
    public byte[] bytes() { return bytes; }
    public int blockSize() { return bytes.length; }
    public String sha256() { return Hex.sha256(bytes); }

    public int magic() { return u16(0); }
    public int type() { return u16(2); }
    public long sequence() { return Integer.toUnsignedLong(readU32(4)); }
    public int headerCrcSlot() { return -1; } // h_blocknr reserved slot, not asserted

    public int u16(int off) {
        return ((bytes[off] & 0xFF)) | ((bytes[off + 1] & 0xFF) << 8);
    }

    public long readU32(int off) {
        return (bytes[off] & 0xFFL)
                | ((bytes[off + 1] & 0xFFL) << 8)
                | ((bytes[off + 2] & 0xFFL) << 16)
                | ((bytes[off + 3] & 0xFFL) << 24);
    }

    public long readU64(int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (bytes[off + i] & 0xFFL);
        return v;
    }

    public void writeU32(int off, long v) {
        bytes[off] = (byte) v;
        bytes[off + 1] = (byte) (v >>> 8);
        bytes[off + 2] = (byte) (v >>> 16);
        bytes[off + 3] = (byte) (v >>> 24);
    }

    public boolean headerLooksValid() {
        return bytes.length >= JBD2.HEADER_SIZE && magic() == JBD2.MAGIC;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof JournalBlock)) return false;
        JournalBlock jb = (JournalBlock) o;
        return slot == jb.slot && Arrays.equals(bytes, jb.bytes);
    }
    @Override public int hashCode() { return 31 * slot + Arrays.hashCode(bytes); }
}
