package org.fslab.journal.jbd2;

/**
 * CRC-32C (Castagnoli), the CRC family used by JBD2 v2/v3 checksums and by
 * ext4 metadata checksums. Implemented without external dependencies so the
 * rule engine is reproducible on a bare JDK.
 */
public final class Crc32c {

    private static final int[] TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int crc = n;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? 0x82f63b78 ^ (crc >>> 1) : crc >>> 1;
            }
            TABLE[n] = crc;
        }
    }

    private Crc32c() {
    }

    /** Seed used by JBD2: lower 32 bits of crc32c(0, journal uuid bytes). */
    public static int seed(byte[] uuid) {
        int crc = 0xffffffff;
        crc = update(crc, uuid, 0, uuid.length);
        return crc ^ 0xffffffff;
    }

    public static int of(byte[] data) {
        return of(data, 0, data.length);
    }

    public static int of(byte[] data, int off, int len) {
        int crc = 0xffffffff;
        crc = update(crc, data, off, len);
        return crc ^ 0xffffffff;
    }

    public static int update(int crc, byte[] data, int off, int len) {
        for (int i = off; i < off + len; i++) {
            crc = TABLE[(crc ^ data[i]) & 0xff] ^ (crc >>> 8);
        }
        return crc;
    }
}
