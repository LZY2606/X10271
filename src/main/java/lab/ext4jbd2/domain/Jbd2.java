package lab.ext4jbd2.domain;

/** On-disk constants of the lab's JBD2-faithful journal format. */
public final class Jbd2 {
    private Jbd2() {}

    public static final long MAGIC = 0xC03B3998L;

    public static final int TYPE_DESCRIPTOR = 1;
    public static final int TYPE_COMMIT = 2;
    public static final int TYPE_REVOKE = 3;
    public static final int TYPE_SUPERBLOCK_V1 = 4;
    public static final int TYPE_SUPERBLOCK_V2 = 5;

    public static final int FLAG_LAST_TAG = 0x8;

    public static final int CHECKSUM_NONE = 0;   // v0: no checksums
    public static final int CHECKSUM_CRC32C = 1; // v1: crc32c

    public static final int HEADER_LEN = 12;
    public static final int TAG_LEN = 12;

    public static String typeName(int t) {
        return switch (t) {
            case TYPE_DESCRIPTOR -> "descriptor";
            case TYPE_COMMIT -> "commit";
            case TYPE_REVOKE -> "revoke";
            case TYPE_SUPERBLOCK_V1 -> "superblock-v1";
            case TYPE_SUPERBLOCK_V2 -> "superblock-v2";
            default -> "unknown(" + t + ")";
        };
    }
}
