package org.fslab.jbd2.core;

/** On-disk JBD2 constants (see linux/include/linux/jbd2.h). */
public final class Jbd2Const {
    private Jbd2Const() {}

    public static final long SUPERBLOCK_V2_MAGIC = 0xC03B3998L;
    public static final int SUPERBLOCK_SIZE = 1024;

    public static final int DESC_BLOCK_MAGIC = 0xC03B3998;
    public static final int COMMIT_BLOCK_MAGIC = 0xC03B3998;

    public static final int DESCRIPTOR_BLOCK = 1;
    public static final int COMMIT_BLOCK = 2;
    public static final int SUPERBLOCK_V2 = 4;
    public static final int REVOKE_BLOCK = 8;

    /* tag flags */
    public static final int FLAG_ESCAPE = 1;
    public static final int FLAG_SAME_UUID = 2;
    public static final int FLAG_DELETED = 4;
    public static final int FLAG_LAST_TAG = 8;

    public static final int TAG_BYTES_32 = 8;
    public static final int TAG_BYTES_64 = 12;
    public static final int UUID_BYTES = 16;

    public static final int HEADER_BYTES = 12;
    public static final int COMMIT_TRAILER_BYTES = 4;
    public static final int REVOKE_COUNT_BYTES = 4;
}
