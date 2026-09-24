package org.jbd2lab.util;

import java.util.zip.CRC32;

/** Checksum algorithms used by the dialect. CRC32C is Castagnoli (reflected, poly 0x1EDC6F41). */
public final class Checksums {
    private static final int[] CRC32C_TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) == 0 ? c >>> 1 : (c >>> 1) ^ 0x82F63B78;
            }
            CRC32C_TABLE[n] = c;
        }
    }

    public static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue() & 0xFFFFFFFFL;
    }

    public static long crc32c(byte[] data) {
        return crc32c(data, 0, data.length);
    }

    public static long crc32c(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc = CRC32C_TABLE[(crc ^ (data[i] & 0xFF)) & 0xFF] ^ (crc >>> 8);
        }
        return (crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }

    private Checksums() {}
}
