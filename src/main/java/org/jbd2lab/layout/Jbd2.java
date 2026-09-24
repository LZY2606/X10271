package org.jbd2lab.layout;

/**
 * Constants for the JBD2-LAB on-disk dialect (documented in docs/LAYOUT.md).
 *
 * <p>The dialect follows Linux JBD2 concepts and big-endian encoding exactly, with a single,
 * explicitly documented simplification: checksum coverage and the tag checksum tail are spelled
 * out literally so fixtures are self-contained and reproducible without kernel internals.
 */
public final class Jbd2 {
    private Jbd2() {}

    public static final long SUPERBLOCK_MAGIC = 0xC03B3998L;

    public static final int DESCRIPTOR_BLOCK = 1;
    public static final int COMMIT_BLOCK = 2;
    public static final int SUPERBLOCK_V2 = 3;
    public static final int SUPERBLOCK_V1 = 4;
    public static final int REVOKE_BLOCK = 5;

    public static final int TAG_FLAG_ESCAPE = 0x1;
    public static final int TAG_FLAG_SAME_UUID = 0x2;
    public static final int TAG_FLAG_DELETED = 0x4;
    public static final int TAG_FLAG_LAST_TAG = 0x8;
    public static final int TAG_FLAG_64BIT = 0x10;

    public static final int INCOMPAT_FEATURE_64BLOCK = 0x1;
    public static final int INCOMPAT_FEATURE_CSUM = 0x2;
    public static final int INCOMPAT_FEATURE_CSUM_V3 = 0x4;

    /** s_feature_incompat value for checksum version 1 (block-tail CRC32C). */
    public static final int INCOMPAT_CSUM_V2 = INCOMPAT_FEATURE_CSUM;
    /** s_feature_incompat value for checksum version 2/3 (per-tag CRC32C tails). */
    public static final int INCOMPAT_CSUM_V3 = INCOMPAT_FEATURE_CSUM | INCOMPAT_FEATURE_CSUM_V3;

    public static final int CSUM_VERSION_0 = 0; // none
    public static final int CSUM_VERSION_1 = 1; // block-tail crc32c
    public static final int CSUM_VERSION_2 = 2; // per-tag crc32c tails

    /** Header common to descriptor / commit / revoke blocks: magic(4) type(4) seq(4). */
    public static final int HEADER_SIZE = 12;
    public static final int TAIL_CSUM_SIZE = 4;

    /** tag: blocknr(8) flags(2) blocknrHigh(4) [tagCrc(4)] */
    public static final int TAG_SIZE_BASE = 14;
    public static final int TAG_SIZE_CSUM = TAG_SIZE_BASE + 4;

    /** Journal superblock v2 used by the lab. */
    public static final int SB_SIZE = 1024;

    /** Map s_feature_incompat -> checksum version dialect. */
    public static int checksumVersion(int incompat) {
        if ((incompat & INCOMPAT_FEATURE_CSUM_V3) != 0) {
            return CSUM_VERSION_2;
        }
        if ((incompat & INCOMPAT_FEATURE_CSUM) != 0) {
            return CSUM_VERSION_1;
        }
        return CSUM_VERSION_0;
    }
}
