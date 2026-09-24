package com.ext4lab.jbd2;

import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/** Checksum versions preserved from the journal superblock. */
public final class Crc {
    public static final int V1_NONE = 1;      // legacy: no per-block checksums
    public static final int V2_CRC32 = 2;     // crc32 (legacy jbd2 v2)
    public static final int V3_CRC32C = 3;    // crc32c (metadata_csum era)

    private Crc() {}

    public static long compute(int version, byte[] data, int off, int len) {
        Checksum c = switch (version) {
            case V3_CRC32C -> new CRC32C();
            case V2_CRC32 -> new CRC32();
            default -> null;
        };
        if (c == null) return 0;
        c.update(data, off, len);
        return c.getValue() & 0xFFFF_FFFFL;
    }

    public static String name(int version) {
        return switch (version) {
            case V3_CRC32C -> "crc32c-v3";
            case V2_CRC32 -> "crc32-v2";
            case V1_NONE -> "none-v1";
            default -> "unknown-" + version;
        };
    }
}
