package org.fslab.jbd2.core;

import java.util.zip.Checksum;

/**
 * CRC-32C (Castagnoli), pure Java, no native dependency.
 * JBD2 checksum type 2 (E2fsck v2 journal checksums) uses CRC-32C.
 */
public final class Crc32c implements Checksum {

    private static final int[] TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int crc = n;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? 0x82F63B78 ^ (crc >>> 1) : crc >>> 1;
            }
            TABLE[n] = crc;
        }
    }

    private int crc = 0xFFFFFFFF;

    public static long of(byte[] data) {
        Crc32c c = new Crc32c();
        c.update(data, 0, data.length);
        return c.getValue();
    }

    public static long of(byte[]... parts) {
        Crc32c c = new Crc32c();
        for (byte[] p : parts) {
            c.update(p, 0, p.length);
        }
        return c.getValue();
    }

    @Override public void update(int b) {
        crc = TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8);
    }

    @Override public void update(byte[] b, int off, int len) {
        int local = crc;
        for (int i = off; i < off + len; i++) {
            local = TABLE[(local ^ b[i]) & 0xFF] ^ (local >>> 8);
        }
        crc = local;
    }

    @Override public long getValue() { return ((long) crc ^ 0xFFFFFFFFL) & 0xFFFFFFFFL; }
    @Override public void reset() { crc = 0xFFFFFFFF; }
}
