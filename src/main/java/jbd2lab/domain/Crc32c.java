package jbd2lab.domain;

/**
 * CRC-32C (Castagnoli), the JBD2 v3 journal checksum algorithm.
 * Table-driven implementation with no external dependency.
 */
public final class Crc32c implements java.util.zip.Checksum {
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

    private int crc = 0xffffffff;

    public static long of(byte[] data, int off, int len) {
        Crc32c c = new Crc32c();
        c.update(data, off, len);
        return c.getValue();
    }

    public static long of(byte[] data) {
        return of(data, 0, data.length);
    }

    @Override
    public void update(int b) {
        crc = TABLE[(crc ^ b) & 0xff] ^ (crc >>> 8);
    }

    @Override
    public void update(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            crc = TABLE[(crc ^ (b[i] & 0xff)) & 0xff] ^ (crc >>> 8);
        }
    }

    @Override
    public long getValue() {
        return (crc ^ 0xffffffff) & 0xffffffffL;
    }

    @Override
    public void reset() {
        crc = 0xffffffff;
    }
}
