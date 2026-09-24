package journal.core;

/**
 * Laboratory encoding of the JBD2 on-disk structures used by this replay room.
 *
 * The layout is a faithful, deliberately small model of the real ext4/JBD2 log:
 * every journal block starts with the 12-byte header, descriptor blocks carry
 * ordered tags, revoke blocks carry revoked filesystem block numbers and
 * commit blocks carry a trailing checksum whose algorithm is selected by the
 * checksum version stored in the journal superblock.
 *
 * All multi-byte integers are big-endian, mirroring the on-disk convention.
 */
public final class JournalFormat {
    private JournalFormat() {}

    public static final int MAGIC = 0xC03B3998;

    public static final int HEADER_SIZE = 12;

    public static final int TYPE_SUPERBLOCK = 0;
    public static final int TYPE_DESCRIPTOR = 1;
    public static final int TYPE_COMMIT = 2;
    public static final int TYPE_REVOKE = 3;

    /** Descriptor tag flag: following data block starts with the journal magic and is escaped. */
    public static final int FLAG_ESCAPE = 0x1;
    public static final int FLAG_LAST_TAG = 0x2;

    /**
     * Checksum versions (the raw byte kept verbatim from the superblock):
     * 0 = legacy/absent, 1 = CRC32 (IEEE), 2 = CRC32C (Castagnoli, JBD2 v2).
     */
    public static final int CKSUM_NONE = 0;
    public static final int CKSUM_CRC32 = 1;
    public static final int CKSUM_CRC32C = 2;

    public static final int[] VALID_BLOCK_SIZES = {1024, 2048, 4096};

    public static boolean isValidBlockSize(int size) {
        for (int valid : VALID_BLOCK_SIZES) if (valid == size) return true;
        return false;
    }

    public static int u8(byte[] b, int off) { return b[off] & 0xFF; }

    public static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    public static void putU32(byte[] b, int off, long value) {
        b[off] = (byte) (value >>> 24);
        b[off + 1] = (byte) (value >>> 16);
        b[off + 2] = (byte) (value >>> 8);
        b[off + 3] = (byte) value;
    }

    public static int headerType(byte[] block) {
        if (block.length < HEADER_SIZE) return -1;
        return (int) u32(block, 4);
    }

    public static long headerSeq(byte[] block) {
        return u32(block, 8);
    }

    public static byte[] header(int type, long seq) {
        byte[] h = new byte[HEADER_SIZE];
        putU32(h, 0, MAGIC);
        putU32(h, 4, type);
        putU32(h, 8, seq);
        return h;
    }

    public static String hexSeq(long seq) {
        return String.format("%08x", seq & 0xFFFFFFFFL);
    }
}
