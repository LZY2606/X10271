package com.ext4lab.jbd2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** On-disk constants for the (simplified but faithful) JBD2 layout used by the lab. */
public final class JournalFormat {
    private JournalFormat() {}

    public static final long MAGIC = 0xC03B3998L;

    public static final int BT_DESCRIPTOR = 1;
    public static final int BT_COMMIT = 2;
    public static final int BT_SUPERBLOCK_V1 = 3;
    public static final int BT_SUPERBLOCK_V2 = 4;
    public static final int BT_REVOKE = 5;

    public static final int FLAG_ESCAPE = 1;
    public static final int FLAG_SAME_UUID = 2;
    public static final int FLAG_DELETED = 4;
    public static final int FLAG_LAST_TAG = 8;

    public static final int TAG_SIZE = 8; // t_blocknr(4) t_checksum(2) t_flags(2)

    public static final int[] VALID_BLOCK_SIZES = {1024, 2048, 4096};

    public static boolean validBlockSize(int bs) {
        for (int v : VALID_BLOCK_SIZES) if (v == bs) return true;
        return false;
    }

    public static ByteBuffer wrap(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
    }

    public static long u32(byte[] b, int off) {
        return Integer.toUnsignedLong(wrap(b).getInt(off));
    }

    public static int u16(byte[] b, int off) {
        return wrap(b).getShort(off) & 0xFFFF;
    }

    public static void put32(byte[] b, int off, long v) {
        wrap(b).putInt(off, (int) (v & 0xFFFF_FFFFL));
    }

    public static void put16(byte[] b, int off, int v) {
        wrap(b).putShort(off, (short) (v & 0xFFFF));
    }

    public static String typeName(int t) {
        return switch (t) {
            case BT_DESCRIPTOR -> "descriptor";
            case BT_COMMIT -> "commit";
            case BT_SUPERBLOCK_V1 -> "superblock-v1";
            case BT_SUPERBLOCK_V2 -> "superblock-v2";
            case BT_REVOKE -> "revoke";
            default -> "unknown-" + t;
        };
    }
}
