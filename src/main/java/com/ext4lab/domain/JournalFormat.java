package com.ext4lab.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Simplified JBD2 on-disk layout (big-endian):
 * header = magic(4) type(4) sequence(4); descriptor tags = fsBlock(4) flags(2) crc16(2);
 * revoke = header count(4) entries(4*n); checksum v3 stores crc32c in the last 4 bytes.
 */
public final class JournalFormat {
    public static final long MAGIC = 0xC03B3998L;
    public static final int TYPE_DESCRIPTOR = 1;
    public static final int TYPE_COMMIT = 2;
    public static final int TYPE_SUPERBLOCK_V2 = 4;
    public static final int TYPE_REVOKE = 5;
    public static final int FLAG_LAST = 0x2;
    public static final int HEADER = 12;
    public static final int TAG_LEN = 8;

    private JournalFormat() {}

    public static long u32(byte[] b, int off) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(b, off, 4).order(ByteOrder.BIG_ENDIAN).getInt());
    }

    public static int u16(byte[] b, int off) {
        return Short.toUnsignedInt(ByteBuffer.wrap(b, off, 2).order(ByteOrder.BIG_ENDIAN).getShort());
    }

    public static void put32(byte[] b, int off, long v) {
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.BIG_ENDIAN).putInt((int) (v & 0xFFFFFFFFL));
    }

    public static void put16(byte[] b, int off, int v) {
        ByteBuffer.wrap(b, off, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) (v & 0xFFFF));
    }

    public static boolean hasMagic(byte[] b) {
        return b.length >= HEADER && u32(b, 0) == MAGIC;
    }

    public static int typeOf(byte[] b) {
        return (int) u32(b, 4);
    }

    public static long seqOf(byte[] b) {
        return u32(b, 8);
    }

    /** crc32c over all but the trailing 4 bytes; stored big-endian at the tail (checksum v3). */
    public static long blockChecksum(byte[] b) {
        CRC32C crc = new CRC32C();
        crc.update(b, 0, b.length - 4);
        return crc.getValue();
    }

    public static long storedChecksum(byte[] b) {
        return u32(b, b.length - 4);
    }

    public static boolean checksumOk(byte[] b, int checksumVersion) {
        if (checksumVersion < 3) return true; // v2 blocks carry no verifiable checksum
        return storedChecksum(b) == blockChecksum(b);
    }

    public static List<Model.Tag> tagsOf(byte[] b) {
        List<Model.Tag> tags = new ArrayList<>();
        int off = HEADER;
        while (off + TAG_LEN <= b.length - 4) {
            long fs = u32(b, off);
            int flags = u16(b, off + 4);
            int crc16 = u16(b, off + 6);
            if (fs == 0 && flags == 0) break; // padding
            tags.add(new Model.Tag(fs, flags, crc16));
            off += TAG_LEN;
            if ((flags & FLAG_LAST) != 0) break;
        }
        return tags;
    }

    public static List<Long> revokeEntries(byte[] b) {
        int count = (int) u32(b, HEADER);
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < count && HEADER + 4 + i * 4 + 4 <= b.length - 4; i++) {
            out.add(u32(b, HEADER + 4 + i * 4));
        }
        return out;
    }

    // ---- builders (fixtures and tests only; never used on imported bytes) ----

    public static byte[] descriptorBlock(int size, long seq, List<Long> fsBlocks, int checksumVersion) {
        byte[] b = new byte[size];
        put32(b, 0, MAGIC);
        put32(b, 4, TYPE_DESCRIPTOR);
        put32(b, 8, seq);
        int off = HEADER;
        for (int i = 0; i < fsBlocks.size(); i++) {
            put32(b, off, fsBlocks.get(i));
            put16(b, off + 4, i == fsBlocks.size() - 1 ? FLAG_LAST : 0);
            put16(b, off + 6, 0);
            off += TAG_LEN;
        }
        seal(b, checksumVersion);
        return b;
    }

    public static byte[] dataBlock(int size, int fill) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    public static byte[] commitBlock(int size, long seq, int checksumVersion) {
        byte[] b = new byte[size];
        put32(b, 0, MAGIC);
        put32(b, 4, TYPE_COMMIT);
        put32(b, 8, seq);
        seal(b, checksumVersion);
        return b;
    }

    public static byte[] revokeBlock(int size, long seq, List<Long> fsBlocks, int checksumVersion) {
        byte[] b = new byte[size];
        put32(b, 0, MAGIC);
        put32(b, 4, TYPE_REVOKE);
        put32(b, 8, seq);
        put32(b, HEADER, fsBlocks.size());
        for (int i = 0; i < fsBlocks.size(); i++) {
            put32(b, HEADER + 4 + i * 4, fsBlocks.get(i));
        }
        seal(b, checksumVersion);
        return b;
    }

    private static void seal(byte[] b, int checksumVersion) {
        if (checksumVersion >= 3) {
            put32(b, b.length - 4, blockChecksum(b));
        }
    }
}
