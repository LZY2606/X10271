package ext4lab.core;

import java.util.zip.CRC32C;

/** JBD2 日志块二进制格式（大端）与校验和工具。 */
public final class Jbd {
    private Jbd() {}

    public static final long MAGIC = 0xC03B3998L;

    public static final int T_DESCRIPTOR = 1;
    public static final int T_COMMIT = 2;
    public static final int T_SUPERBLOCK_V1 = 3;
    public static final int T_SUPERBLOCK_V2 = 4;
    public static final int T_REVOKE = 5;
    public static final int T_REVOKE3 = 6;

    public static final int FLAG_ESCAPE = 1;
    public static final int FLAG_SAME_UUID = 2;
    public static final int FLAG_DELETED = 4;
    public static final int FLAG_LAST = 8;

    public static long be32(byte[] b, int off) {
        return ((b[off] & 0xffL) << 24) | ((b[off + 1] & 0xffL) << 16)
                | ((b[off + 2] & 0xffL) << 8) | (b[off + 3] & 0xffL);
    }

    public static int be16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    public static void putBe32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static void putBe16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    /** crc32c(b[from..to))，与 jbd2 的 crc32c 语义一致。 */
    public static long crc32c(byte[] b, int from, int to) {
        CRC32C crc = new CRC32C();
        crc.update(b, from, to - from);
        return crc.getValue();
    }
}
