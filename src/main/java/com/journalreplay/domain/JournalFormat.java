package com.journalreplay.domain;

/** On-disk JBD2 constants (see include/linux/jbd2.h). */
public final class JournalFormat {

    private JournalFormat() {}

    public static final long MAGIC = 0xC03B3998L;

    public static final int BLOCK_DESCRIPTOR = 1;
    public static final int BLOCK_COMMIT = 2;
    public static final int BLOCK_SUPER_V1 = 3;
    public static final int BLOCK_SUPER_V2 = 4;
    public static final int BLOCK_REVOKE = 5;

    public static final int FLAG_ESCAPE = 1;
    public static final int FLAG_SAME_UUID = 2;
    public static final int FLAG_DELETED = 4;
    public static final int FLAG_LAST_TAG = 8;

    public static final int FEATURE_INCOMPAT_REVOKE = 0x1;
    public static final int FEATURE_INCOMPAT_64BIT = 0x2;
    public static final int FEATURE_INCOMPAT_ASYNC_COMMIT = 0x4;
    public static final int FEATURE_INCOMPAT_CSUM_V2 = 0x8;
    public static final int FEATURE_INCOMPAT_CSUM_V3 = 0x10;

    /** Header: magic(4) + blocktype(4) + sequence(4). */
    public static final int HEADER_SIZE = 12;
    /** Revoke header: journal header + r_count(4). */
    public static final int REVOKE_HEADER_SIZE = 16;
    /** Commit trailer: checksum(4) at bytes 16..20 of the commit block. */
    public static final int COMMIT_CSUM_OFFSET = 16;
    /** Tail checksum occupies the last 4 bytes of descriptor/revoke blocks (csum v2/v3). */
    public static final int TAIL_SIZE = 4;

    /** Tag size in bytes for a given checksum version / 64-bit feature combination. */
    public static int tagSize(boolean csumV3, boolean has64bit) {
        if (csumV3) return 16;          // journal_block_tag3_t
        return has64bit ? 12 : 8;       // journal_block_tag_t
    }

    public static String typeName(int t) {
        return switch (t) {
            case BLOCK_DESCRIPTOR -> "descriptor";
            case BLOCK_COMMIT -> "commit";
            case BLOCK_SUPER_V1 -> "superblock-v1";
            case BLOCK_SUPER_V2 -> "superblock-v2";
            case BLOCK_REVOKE -> "revoke";
            default -> "unknown(" + t + ")";
        };
    }
}
