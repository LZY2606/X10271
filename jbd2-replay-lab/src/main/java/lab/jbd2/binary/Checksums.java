package lab.jbd2.binary;

import java.util.zip.CRC32;
import java.util.zip.Checksum;

/**
 * Checksum algorithms used by the two journal checksum flavours. The lab uses the
 * standard IEEE CRC32 (v1) and Castagnoli CRC32C (v2), both provided by the JDK so the
 * repository stays self-contained.
 */
public final class Checksums {

    private Checksums() {
    }

    public static long crc32(byte[] data, int off, int len) {
        CRC32 c = new CRC32();
        c.update(data, off, len);
        return c.getValue() & 0xffffffffL;
    }

    public static long crc32c(byte[] data, int off, int len) {
        Checksum c = new java.util.zip.CRC32C();
        c.update(data, off, len);
        return c.getValue() & 0xffffffffL;
    }

    public static long checksum(int version, byte[] data, int off, int len) {
        return switch (version) {
            case 1 -> crc32(data, off, len);
            case 2 -> crc32c(data, off, len);
            default -> 0L;
        };
    }
}
