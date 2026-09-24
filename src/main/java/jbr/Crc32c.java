package jbr;

/** CRC-32C (Castagnoli), reflected polynomial 0x82F63B78. */
public final class Crc32c {
    private static final int[] TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ 0x82F63B78 : c >>> 1;
            }
            TABLE[i] = c;
        }
    }

    private Crc32c() {}

    public static int compute(byte[] data) {
        return compute(0, data, 0, data.length);
    }

    public static int compute(int seed, byte[] data, int off, int len) {
        int crc = ~seed;
        for (int i = off; i < off + len; i++) {
            crc = TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return ~crc;
    }
}
