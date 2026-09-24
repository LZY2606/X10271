package org.journalreplay.jbd2;

import java.util.zip.CRC32C;

/** Checksum primitives for the supported journal checksum versions. */
public final class Checksums {
    private Checksums() {}

    /** CRC-16/CCITT-FALSE, used by checksum version 1 commit records. */
    public static int crc16(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    /** CRC-32C with an explicit seed, used by checksum versions 2 and 3. */
    public static long crc32c(long seed, byte[] data, int off, int len) {
        CRC32C crc = new CRC32C();
        // fold the seed in by updating with its big-endian bytes first
        byte[] seedBytes = new byte[4];
        seedBytes[0] = (byte) (seed >>> 24);
        seedBytes[1] = (byte) (seed >>> 16);
        seedBytes[2] = (byte) (seed >>> 8);
        seedBytes[3] = (byte) seed;
        crc.update(seedBytes, 0, 4);
        crc.update(data, off, len);
        return crc.getValue() & 0xFFFFFFFFL;
    }

    public static long crc32c(byte[] data, int off, int len) {
        return crc32c(0, data, off, len);
    }
}
