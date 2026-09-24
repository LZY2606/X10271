package lab.jbd2.core;

/** CRC-32C (Castagnoli), table driven, big-endian friendly. */
public final class Crc32c {
    private static final int POLY = 0x82F63B78;
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ POLY : c >>> 1;
            }
            TABLE[i] = c;
        }
    }

    private Crc32c() {}

    public static long compute(byte[]... parts) {
        int crc = 0xFFFF_FFFF;
        for (byte[] part : parts) {
            for (byte b : part) {
                crc = TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8);
            }
        }
        return (crc ^ 0xFFFF_FFFF) & 0xFFFF_FFFFL;
    }
}
