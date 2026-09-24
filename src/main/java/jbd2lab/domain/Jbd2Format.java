package jbd2lab.domain;

import java.util.Set;

/**
 * On-disk constants for the bundled JBD2-style journal codec.
 * Layout follows jbd2 journal block headers (big-endian magic/type/sequence),
 * 16-byte descriptor tags, v3 CRC32C checksums and v1-style revoke blocks.
 */
public final class Jbd2Format {
    private Jbd2Format() {}

    public static final long JBD2_MAGIC = 0xc03b3998L;

    public static final int DESC_BLOCK = 1;
    public static final int COMMIT_BLOCK = 2;
    public static final int SUPERBLOCK_V1 = 3;
    public static final int SUPERBLOCK_V2 = 4;
    public static final int REVOKE_BLOCK = 5;

    public static final int TAG_FLAG_ESCAPE = 1;
    public static final int TAG_FLAG_SAME_UUID = 2;
    public static final int TAG_FLAG_DELETED = 4;
    public static final int TAG_FLAG_LAST_TAG = 8;
    public static final int TAG_FLAG_LARGE_TAG = 16;

    public static final int BLOCK_HEADER_SIZE = 12;
    public static final int TAG_SIZE = 16; // 64-bit blocknr + 4 flags + 4 blockCRC (always large-tag form)
    public static final int CHECKSUM_SIZE = 4;
    public static final int COMMIT_CHECKSUM_OFF = 20; // 12 header + 4 chksumType + 4 sec
    public static final int REVOKE_COUNT_OFF = 12;
    public static final int REVOKE_ENTRIES_OFF = 16;

    /** Checksum versions modelled. 0 = unchecked (v1), 3 = CRC32C (v3, matches ext4 feature). */
    public static final int CKSUM_V1_NONE = 0;
    public static final int CKSUM_V3_CRC32C = 3;
    public static final Set<Integer> VALID_CKSUM_VERSIONS = Set.of(CKSUM_V1_NONE, CKSUM_V3_CRC32C);

    /** ext4 block sizes accepted by mkfs/ext4; anything else is an invalid-block-size case. */
    public static final Set<Integer> VALID_BLOCK_SIZES = Set.of(1024, 2048, 4096);

    public static String typeName(int type) {
        return switch (type) {
            case DESC_BLOCK -> "descriptor";
            case COMMIT_BLOCK -> "commit";
            case SUPERBLOCK_V1 -> "superblock_v1";
            case SUPERBLOCK_V2 -> "superblock_v2";
            case REVOKE_BLOCK -> "revoke";
            default -> "unknown(0x" + Integer.toHexString(type) + ")";
        };
    }
}
