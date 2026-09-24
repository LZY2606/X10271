package jrnl.jbd2;

/**
 * CRC32C (Castagnoli) used by JBD2 checksum v3. Pure Java table-driven
 * implementation; no native dependency.
 */
public final class Crc32c {

    private static final int[] TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (0x82F63B78 ^ (c >>> 1)) : (c >>> 1);
            }
            TABLE[n] = c;
        }
    }

    private Crc32c() {
    }

    public static long crc32c(byte[] data) {
        int crc = 0xFFFFFFFF;
        for (byte b : data) {
            crc = TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8);
        }
        return (crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }
}
