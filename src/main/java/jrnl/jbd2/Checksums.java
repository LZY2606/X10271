package jrnl.jbd2;

import jrnl.domain.ChecksumVersion;

import java.util.zip.CRC32;

/**
 * Fixture checksum convention (documented in fixtures/lab-case/README.md):
 *
 *  V2 (crc32)  - descriptor blocks: crc32 over the whole block bytes with the
 *                trailing 4-byte checksum field zeroed;
 *              - commit blocks: crc32 over bytes [0 .. tailOffset) of the block,
 *                i.e. header + commit sequence, checksum stored in the last 4.
 *  V3 (crc32c) - identical coverage, Castagnoli polynomial.
 *  NONE        - no checksum bytes are interpreted; always reported OK.
 */
public final class Checksums {

    private Checksums() {
    }

    public static long compute(ChecksumVersion version, byte[] covered) {
        return switch (version) {
            case NONE -> 0L;
            case V2_CRC32 -> crc32(covered);
            case V3_CRC32C -> Crc32c.crc32c(covered);
        };
    }

    static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** Coverage used for descriptor blocks: entire block with checksum field zeroed. */
    public static byte[] descriptorCoverage(byte[] block) {
        byte[] cov = block.clone();
        zeroTail(cov, 4);
        return cov;
    }

    /** Coverage used for commit blocks: header (12) + commit sequence (4); tail is checksum. */
    public static byte[] commitCoverage(byte[] block) {
        int end = Math.min(16, block.length);
        byte[] cov = new byte[end];
        System.arraycopy(block, 0, cov, 0, end);
        return cov;
    }

    private static void zeroTail(byte[] buf, int n) {
        for (int i = buf.length - n; i < buf.length; i++) {
            buf[i] = 0;
        }
    }
}
