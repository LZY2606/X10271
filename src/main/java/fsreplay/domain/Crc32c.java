package fsreplay.domain;

/** CRC-32C (Castagnoli), the checksum JBD2 uses for metadata v2. */
public final class Crc32c {
    private static final int[] TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0x82F63B78 : c >>> 1;
            TABLE[i] = c;
        }
    }
    private Crc32c() {}

    public static long compute(byte[] data) { return compute(data, 0, data.length); }

    public static long compute(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) crc = TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        return (crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }

    public static long computeChunks(byte[]... chunks) {
        int crc = 0xFFFFFFFF;
        for (byte[] data : chunks)
            for (byte b : data) crc = TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8);
        return (crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }
}
