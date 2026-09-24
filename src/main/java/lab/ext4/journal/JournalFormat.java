package lab.ext4.journal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** On-disk layout constants for the JBD2 journal blocks handled by this lab. */
public final class JournalFormat {
    public static final long MAGIC = 0xC03B3998L;

    public static final int TYPE_DESCRIPTOR = 1;
    public static final int TYPE_COMMIT = 2;
    public static final int TYPE_SUPERBLOCK_V1 = 3;
    public static final int TYPE_SUPERBLOCK_V2 = 4;
    public static final int TYPE_REVOKE = 5;

    public static final int HEADER_LEN = 12;      // magic(4) type(4) seq(4)
    public static final int TAG_LEN = 16;         // blockno(4) flags(4) tagCrc(4) reserved(4)
    public static final int COMMIT_CRC_OFF = 12;  // v3 commit checksum field
    public static final int REVOKE_COUNT_OFF = 12;
    public static final int REVOKE_ENTRIES_OFF = 16;

    public static final int FLAG_ESCAPE = 1;
    public static final int FLAG_SAME_UUID = 2;
    public static final int FLAG_DELETED = 4;
    public static final int FLAG_LAST_TAG = 8;

    private JournalFormat() {}

    public static ByteBuffer wrap(byte[] block) {
        return ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
    }

    public static long magic(byte[] block) {
        return Integer.toUnsignedLong(wrap(block).getInt(0));
    }

    public static int blockType(byte[] block) {
        return wrap(block).getInt(4);
    }

    public static long sequence(byte[] block) {
        return Integer.toUnsignedLong(wrap(block).getInt(8));
    }

    public static boolean isValidBlockSize(int blockSize) {
        return blockSize >= 1024 && blockSize <= 65536 && (blockSize & (blockSize - 1)) == 0;
    }

    /** CRC32C of a commit block with the checksum field zeroed (v3 semantics). */
    public static long commitChecksum(byte[] block) {
        byte[] copy = block.clone();
        copy[COMMIT_CRC_OFF] = 0;
        copy[COMMIT_CRC_OFF + 1] = 0;
        copy[COMMIT_CRC_OFF + 2] = 0;
        copy[COMMIT_CRC_OFF + 3] = 0;
        return Crc32c.compute(copy);
    }

    public static long storedCommitChecksum(byte[] block) {
        return Integer.toUnsignedLong(wrap(block).getInt(COMMIT_CRC_OFF));
    }

    public static String typeName(int type) {
        return switch (type) {
            case TYPE_DESCRIPTOR -> "descriptor";
            case TYPE_COMMIT -> "commit";
            case TYPE_SUPERBLOCK_V1 -> "superblock_v1";
            case TYPE_SUPERBLOCK_V2 -> "superblock_v2";
            case TYPE_REVOKE -> "revoke";
            default -> "unknown(" + type + ")";
        };
    }
}
