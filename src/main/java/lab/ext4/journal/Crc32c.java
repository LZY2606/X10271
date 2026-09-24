package lab.ext4.journal;

/** CRC-32C (Castagnoli), reflected, polynomial 0x82F63B78. Used by JBD2 v3 checksums. */
public final class Crc32c {
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0x82F63B78 : 0);
            }
            TABLE[i] = crc;
        }
    }

    private Crc32c() {}

    public static long compute(byte[] data) {
        return compute(data, 0, data.length, 0);
    }

    public static long compute(byte[] data, int off, int len, int seed) {
        int crc = ~seed;
        for (int i = off; i < off + len; i++) {
            crc = TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return Integer.toUnsignedLong(~crc);
    }
}
