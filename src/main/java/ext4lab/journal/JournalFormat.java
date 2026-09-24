package ext4lab.journal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

/** Simplified-but-faithful JBD2 journal block layout constants and helpers. */
public final class JournalFormat {
    public static final long MAGIC = 0xC03B3998L;
    public static final int TYPE_DESCRIPTOR = 1;
    public static final int TYPE_COMMIT = 2;
    public static final int TYPE_SUPERBLOCK_V1 = 3;
    public static final int TYPE_SUPERBLOCK_V2 = 4;
    public static final int TYPE_REVOKE = 5;

    public static final int TAG_LAST = 0x8;
    public static final int HEADER_LEN = 12;   // magic(4) type(4) seq(4)
    public static final int TAG_LEN = 8;       // block(4) flags(2) csum16(2)

    private JournalFormat() {}

    public static String typeName(int t) {
        return switch (t) {
            case TYPE_DESCRIPTOR -> "descriptor";
            case TYPE_COMMIT -> "commit";
            case TYPE_SUPERBLOCK_V1 -> "superblock-v1";
            case TYPE_SUPERBLOCK_V2 -> "superblock-v2";
            case TYPE_REVOKE -> "revoke";
            default -> "unknown(" + t + ")";
        };
    }

    /** crc32 (JBD2 checksum v2 style) over full blocks. */
    public static long commitChecksumV2(byte[]... blocks) {
        CRC32 crc = new CRC32();
        for (byte[] b : blocks) crc.update(b, 0, b.length);
        return crc.getValue() & 0xFFFFFFFFL;
    }

    /** crc32c with 16-byte uuid prefix (JBD2 checksum v3 style). */
    public static long commitChecksumV3(byte[] uuid16, byte[]... blocks) {
        CRC32C crc = new CRC32C();
        crc.update(uuid16, 0, 16);
        for (byte[] b : blocks) crc.update(b, 0, b.length);
        return crc.getValue() & 0xFFFFFFFFL;
    }

    public static ByteBuffer buf(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
    }

    public static void putHeader(byte[] block, int type, long seq) {
        ByteBuffer bb = buf(block);
        bb.putInt(0, (int) MAGIC);
        bb.putInt(4, type);
        bb.putInt(8, (int) seq);
    }

    /**
     * Ring-relative sequence comparison. Sequences are 32-bit and wrap; ordering
     * must be relative to the ring start sequence, never plain integer sort.
     * Returns negative if a is older than b relative to {@code firstSeq}.
     */
    public static int seqCompare(long a, long b, long firstSeq) {
        long da = (a - firstSeq) & 0xFFFFFFFFL;
        long db = (b - firstSeq) & 0xFFFFFFFFL;
        return Long.compare(da, db);
    }

    /** true if seq looks like a stale previous-generation sequence. */
    public static boolean isStale(long seq, long firstSeq) {
        long d = (seq - firstSeq) & 0xFFFFFFFFL;
        return d >= 0x80000000L;
    }
}
