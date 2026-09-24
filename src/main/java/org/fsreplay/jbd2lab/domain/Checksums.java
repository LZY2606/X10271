package org.fsreplay.jbd2lab.domain;

import java.util.zip.CRC32;
import java.util.zip.CRC32C;

/**
 * Journal checksum algorithms. JBD2 history is retained explicitly via
 * {@code checksumVersion}: v1 = CRC32 (legacy), v2 = CRC32C (ext4 csum v2).
 * The checksum covers every byte of the block with the trailing checksum
 * field zeroed, exactly as the scanner recomputes it.
 */
public final class Checksums {

    private Checksums() {
    }

    /** v1 = CRC32, v2 = CRC32C; anything else is an unsupported version. */
    public static boolean isSupportedVersion(int version) {
        return version == 1 || version == 2;
    }

    public static long compute(int checksumVersion, byte[] block, int offset, int length) {
        if (checksumVersion == 1) {
            CRC32 crc = new CRC32();
            crc.update(block, offset, length);
            return crc.getValue();
        }
        if (checksumVersion == 2) {
            CRC32C crc = new CRC32C();
            crc.update(block, offset, length);
            return crc.getValue();
        }
        throw new IllegalArgumentException("unsupported checksum version " + checksumVersion);
    }

    public static String algorithmName(int version) {
        return switch (version) {
            case 1 -> "CRC32";
            case 2 -> "CRC32C";
            default -> "UNSUPPORTED-v" + version;
        };
    }
}
