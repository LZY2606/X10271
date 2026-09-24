package org.fslab.journal.jbd2;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Binary layout constants for the laboratory JBD2-inspired on-disk journal.
 *
 * <p>The layout deliberately mirrors ext4/JBD2 conventions (magic numbers, block
 * types, descriptor tags, revoke blocks, ring structure and checksum versions)
 * while remaining fully self contained. Every multi-byte integer is big endian,
 * matching {@code JBD2_NR_BYTES_PER_*} / on-disk JBD2 headers.
 */
public final class Jbd2 {

    public static final long SUPERBLOCK_MAGIC = 0xc03b3998L;

    public static final int HEADER_MAGIC = 0xc03b3998;

    public static final int TYPE_DESC_BLOCK = 1;
    public static final int TYPE_COMMIT_BLOCK = 2;
    public static final int TYPE_SUPERBLOCK_V1 = 3;
    public static final int TYPE_SUPERBLOCK_V2 = 4;
    public static final int TYPE_REVOKE_BLOCK = 5;

    public static final int ESCAPE_MAGIC = 0xbdbdbdbd;

    /** Descriptor tag flag: 64bit block numbers. */
    public static final int TAG_FLAG_ESCAPE = 0x1;
    public static final int TAG_FLAG_SAME_UUID = 0x2;
    public static final int TAG_FLAG_DELETED = 0x4;
    public static final int TAG_FLAG_LAST_TAG = 0x8;
    public static final int TAG_FLAG_LARGE_TAG = 0x10;
    public static final int TAG_FLAG_64BIT = 0x00000008;

    /** Superblock feature flags used by this lab. */
    public static final int FEATURE_COMPAT_CHECKSUM = 0x1;
    public static final int FEATURE_INCOMPAT_REVOKE = 0x1;
    public static final int FEATURE_INCOMPAT_64BIT = 0x2;
    public static final int FEATURE_INCOMPAT_CHECKSUM_V3 = 0x4;
    public static final int FEATURE_INCOMPAT_ASYNC_COMMIT = 0x8;

    /** Checksum versions recognised by the rule engine. */
    public static final int CHECKSUM_V0_NONE = 0;
    public static final int CHECKSUM_V2_TX = 2;
    public static final int CHECKSUM_V3_BLOCK = 3;

    public static final Set<Integer> VALID_BLOCK_SIZES = Set.of(1024, 2048, 4096);

    /** Header: magic(4) type(4) sequence(4) */
    public static final int HEADER_SIZE = 12;
    public static final int SUPERBLOCK_SIZE = 1024;

    /** Tag: blocknr(8) flags(2 or 4) + optional block checksum (v3). */
    public static final int TAG_LARGE_SIZE = 16;
    public static final int TAG_SMALL_SIZE = 12;

    /** Commit block body after header: checksum type(1) + total checksum(4) + padding. */
    public static final int COMMIT_BODY_SIZE = 8;

    public static final int REVOKE_HEADER_SIZE = 28;
    public static final int REVOKE_COUNT_OFFSET = 24;

    private Jbd2() {
    }

    public static String typeName(int type) {
        return switch (type) {
            case TYPE_DESC_BLOCK -> "descriptor";
            case TYPE_COMMIT_BLOCK -> "commit";
            case TYPE_SUPERBLOCK_V1 -> "superblock_v1";
            case TYPE_SUPERBLOCK_V2 -> "superblock_v2";
            case TYPE_REVOKE_BLOCK -> "revoke";
            default -> "unknown(0x" + Integer.toHexString(type) + ")";
        };
    }

    public static void put32(byte[] buf, int off, long value) {
        buf[off] = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }

    public static long get32(byte[] buf, int off) {
        return ((long) (buf[off] & 0xff) << 24)
                | ((long) (buf[off + 1] & 0xff) << 16)
                | ((long) (buf[off + 2] & 0xff) << 8)
                | (buf[off + 3] & 0xff);
    }

    public static void put16(byte[] buf, int off, int value) {
        buf[off] = (byte) (value >>> 8);
        buf[off + 1] = (byte) value;
    }

    public static int get16(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
    }

    public static void put8(byte[] buf, int off, int value) {
        buf[off] = (byte) value;
    }

    public static int get8(byte[] buf, int off) {
        return buf[off] & 0xff;
    }

    public static void putUuid(byte[] buf, int off, byte[] uuid) {
        System.arraycopy(uuid, 0, buf, off, 16);
    }

    public static byte[] asciiUuid(String text) {
        String hex = text.replace("-", "");
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String uuidText(byte[] uuid) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02x", uuid[i]));
            if (i == 3 || i == 7 || i == 9 || i == 11) {
                sb.append('-');
            }
        }
        return sb.toString();
    }

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
