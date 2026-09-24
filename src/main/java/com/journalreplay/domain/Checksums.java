package com.journalreplay.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/**
 * Lab checksum recipes, modelled on JBD2 csum v2/v3 (uuid-seeded CRC32C).
 * The same code computes and verifies, so fixtures and scanner always agree.
 *
 *  - tag checksum (v2): crc32c(uuid || seq || data block) truncated to 16 bits
 *  - tag checksum (v3): crc32c(uuid || seq || data block), full 32 bits
 *  - commit checksum:   crc32c(uuid || seq || commit block with csum field zeroed)
 *  - block tail (v2/v3 descriptor & revoke): crc32c(uuid || seq || block with tail zeroed)
 */
public final class Checksums {

    private Checksums() {}

    public static long crc32c(byte[]... parts) {
        CRC32C crc = new CRC32C();
        for (byte[] p : parts) crc.update(p, 0, p.length);
        return crc.getValue();
    }

    static byte[] be32(long v) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) v).array();
    }

    public static long tagChecksum(byte[] uuid, long seq, byte[] dataBlock) {
        return crc32c(uuid, be32(seq), dataBlock);
    }

    public static int tagChecksum16(byte[] uuid, long seq, byte[] dataBlock) {
        return (int) (tagChecksum(uuid, seq, dataBlock) & 0xFFFF);
    }

    public static long commitChecksum(byte[] uuid, long seq, byte[] commitBlock) {
        byte[] copy = commitBlock.clone();
        for (int i = JournalFormat.COMMIT_CSUM_OFFSET; i < JournalFormat.COMMIT_CSUM_OFFSET + 4; i++) copy[i] = 0;
        return crc32c(uuid, be32(seq), copy);
    }

    public static long tailChecksum(byte[] uuid, long seq, byte[] block) {
        byte[] copy = block.clone();
        for (int i = copy.length - JournalFormat.TAIL_SIZE; i < copy.length; i++) copy[i] = 0;
        return crc32c(uuid, be32(seq), copy);
    }
}
