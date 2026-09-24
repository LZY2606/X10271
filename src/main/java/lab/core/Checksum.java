package lab.core;

import java.util.zip.CRC32;

/** JBD2 块校验。v3=crc32c（castagnoli），v2=crc32。域内仅实现两者的可重复计算。 */
public final class Checksum {

    private Checksum() {}

    public static long compute(String version, byte[] data, int off, int len) {
        return switch (version) {
            case "crc32c" -> Crc32c.compute(data, off, len) & 0xffffffffL;
            case "crc32" -> {
                CRC32 c = new CRC32();
                c.update(data, off, len);
                yield c.getValue();
            }
            default -> throw new IllegalArgumentException("未知 checksum 版本: " + version);
        };
    }

    public static long compute(String version, byte[] data) {
        return compute(version, data, 0, data.length);
    }

    static final class Crc32c {
        private static final int[] TABLE = new int[256];
        static {
            for (int n = 0; n < 256; n++) {
                int c = n;
                for (int k = 0; k < 8; k++) {
                    c = (c & 1) != 0 ? 0x82F63B78 ^ (c >>> 1) : c >>> 1;
                }
                TABLE[n] = c;
            }
        }

        static long compute(byte[] b, int off, int len) {
            int c = 0xffffffff;
            for (int i = off; i < off + len; i++) {
                c = TABLE[(c ^ b[i]) & 0xff] ^ (c >>> 8);
            }
            return ~c & 0xffffffffL;
        }
    }
}
