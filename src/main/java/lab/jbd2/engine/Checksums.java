package lab.jbd2.engine;

import java.util.zip.CRC32;

/**
 * checksum 算法（离线模型，语义对齐 JBD2）：
 *  0 = CRC32  (jbd2 v2/旧版 jbd2)
 *  1 = CRC32C (jbd2 v3, 见 jbd2_journal_get_csum_type)
 * 提交块 checksum 覆盖该事务在环上除提交块以外的全部字节，
 * 计算以 sequence 为种子（对齐 jbd2_chksum 的 crc32c 初值处理）。
 */
public final class Checksums {
    public static final long CRC32 = 0;
    public static final long CRC32C = 1;

    private Checksums() {}

    public static long nameToType(String name) {
        return switch (name == null ? "" : name.trim().toLowerCase()) {
            case "crc32c", "1" -> CRC32C;
            default -> CRC32;
        };
    }

    public static String typeName(long type) {
        return type == CRC32C ? "crc32c" : "crc32";
    }

    public static long crc32(long seed, byte[] data, int off, int len) {
        CRC32 c = new CRC32();
        if (seed != 0) c.update(intBytes((int) seed));
        c.update(data, off, len);
        return c.getValue();
    }

    /**
     * CRC32C (Castagnoli)。JDK 无公开实现，这里用标准查表实现，
     * 与 Linux crc32c 对相同输入一致（reflected, poly 0x82F63B78）。
     */
    private static final int[] CRC32C_TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0x82F63B78 ^ (c >>> 1) : c >>> 1;
            }
            CRC32C_TABLE[n] = c;
        }
    }

    public static long crc32c(long seed, byte[] data, int off, int len) {
        int crc = (int) seed ^ 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc = CRC32C_TABLE[(crc ^ (data[i] & 0xff)) & 0xff] ^ (crc >>> 8);
        }
        return (crc ^ 0xFFFFFFFF) & 0xffffffffL;
    }

    public static long compute(long type, long seed, byte[] data, int off, int len) {
        return type == CRC32C ? crc32c(seed, data, off, len)
                             : crc32(seed, data, off, len);
    }

    private static byte[] intBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16),
                (byte) (v >>> 8), (byte) v};
    }
}
