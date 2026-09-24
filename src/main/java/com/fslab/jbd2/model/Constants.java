package com.fslab.jbd2.model;

/**
 * Lab JBD2 wire constants. The fixture format mirrors the on-disk JBD2 v2 layout
 * (journal_header_s / journal_block_tag_s / journal_commit_header / revoke table)
 * in big-endian order, restricted to the fields the lab reasons about. The exact
 * lab layout is documented in cases/README.md; nothing here invents kernel values
 * beyond the standard magic numbers.
 */
public final class Constants {
    private Constants() {}

    public static final long JBD2_MAGIC = 0xC03B3998L;

    public static final int JBD2_DESCRIPTOR_BLOCK = 1;
    public static final int JBD2_COMMIT_BLOCK = 2;
    public static final int JBD2_SUPERBLOCK_V2 = 4;
    public static final int JBD2_REVOKE_BLOCK = 5;

    public static final int JBD2_FLAG_ESCAPE = 0x1;
    public static final int JBD2_FLAG_SAME_UUID = 0x2;
    public static final int JBD2_FLAG_DELETED = 0x4;
    public static final int JBD2_FLAG_LAST_TAG = 0x8;

    public static final int JBD2_FEATURE_INCOMPAT_64BIT = 0x2;
    public static final int JBD2_FEATURE_INCOMPAT_CSUM_V2 = 0x10;

    /** checksum v1: none; v2: whole-commit CRC32 guard (lab interpretation, documented). */
    public static final int JBD2_CKSUM_V1_NONE = 1;
    public static final int JBD2_CKSUM_V2_CRC32 = 2;

    public static final String RULE_VERSION = "rules-2026.09-jbd2-lab-v1";

    public static final String UNIT_BYTES = "bytes";
    public static final String UNIT_JOURNAL_PHYS_BLOCK = "journal-physical-block(0-based)";
    public static final String UNIT_RING_POSITION = "ring-position-from-s_start(0-based)";
    public static final String UNIT_FS_BLOCKNR = "filesystem-block-number";
    public static final String UNIT_TXN_SEQ = "jbd2-sequence-uint32-ring";

    public static final long SEQ_MODULUS = 0x100000000L;
}
