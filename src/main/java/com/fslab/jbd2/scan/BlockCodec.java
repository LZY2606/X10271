package com.fslab.jbd2.scan;

import com.fslab.jbd2.model.Constants;
import com.fslab.jbd2.model.DescriptorTag;
import com.fslab.jbd2.model.SuperblockInfo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Big-endian codec for the documented lab JBD2 v2 wire layout (see cases/README.md).
 * Layout subset: journal_header_s, journal_superblock_s v2 subset, journal_block_tag_s,
 * journal_revoke_header_s (lab), journal_commit_header. Checksums are CRC32 guards.
 * The parser never mutates the retained original bytes.
 */
public final class BlockCodec {
    private BlockCodec() {}

    // ---- journal_header_s ----
    public static long hMagic(byte[] b)  { return be32(b, 0); }
    public static long hType(byte[] b)   { return be32(b, 4); }
    public static long hSequence(byte[] b) { return be32(b, 8); }

    // ---- lab journal_superblock_s v2 offsets ----
    static final int SB_FEATURE_COMPAT = 28;
    static final int SB_FEATURE_INCOMPAT = 30;
    static final int SB_FEATURE_RO_COMPAT = 32;
    static final int SB_BLOCKSIZE = 34;
    static final int SB_MAXLEN = 38;
    static final int SB_S_START = 42; // u64
    static final int SB_MAX_TXN = 50;
    static final int SB_MAX_TXN_DATA = 54;
    static final int SB_NR_USERS = 58;
    static final int SB_CKSUM_TYPE = 62;
    static final int SB_CKSUM_AT = 66; // u32, guards bytes [0,66)
    static final int SB_CKSUM_REGION_END = 66;

    // ---- descriptor block ----
    static final int DESC_TAG_START = 28; // after journal header + 16-byte uuid
    static final int TAG_SIZE_32 = 8;
    static final int TAG_SIZE_64 = 12;

    // ---- lab revoke header ----
    static final int REV_COUNT_AT = 12;
    static final int REV_ENTRIES_AT = 16;

    // ---- commit block ----
    static final int COMMIT_CKSUM_AT = 12; // 32-byte area; CRC32 stored big-endian in first 4
    static final int COMMIT_CKSUM_AREA = 32;

    public static boolean hasMagic(byte[] b) {
        return b != null && b.length >= 12 && be32(b, 0) == Constants.JBD2_MAGIC;
    }

    public static SuperblockInfo parseSuperblock(byte[] b) {
        long magic = be32(b, 0);
        int blocktype = (int) be32(b, 4);
        int compat = (int) be16(b, SB_FEATURE_COMPAT);
        int incompat = (int) be16(b, SB_FEATURE_INCOMPAT);
        int rocompat = (int) be16(b, SB_FEATURE_RO_COMPAT);
        long sStart = be64(b, SB_S_START);
        long maxLen = be32(b, SB_MAXLEN);
        int cksumType = b[SB_CKSUM_TYPE] & 0xFF;
        long stored = be32(b, SB_CKSUM_AT);
        byte[] region = Arrays.copyOfRange(b, 0, SB_CKSUM_REGION_END);
        long computed = crc32(region);
        return new SuperblockInfo(magic, blocktype, compat, incompat, rocompat,
                sStart, maxLen, cksumType, stored, computed, stored == computed,
                bytesHex(b));
    }

    /** Parses descriptor tags until LAST_TAG; honors 64bit feature layout. */
    public static List<DescriptorTag> parseDescriptor(byte[] b, boolean sixtyFourBit) {
        List<DescriptorTag> tags = new ArrayList<>();
        int tagSize = sixtyFourBit ? TAG_SIZE_64 : TAG_SIZE_32;
        int at = DESC_TAG_START;
        int index = 0;
        while (at + tagSize <= b.length) {
            long low = be32(b, at);
            long flags = be32(b, at + 4);
            long high = sixtyFourBit ? be32(b, at + 8) : 0;
            long fsBlock = (high << 32) | low;
            boolean last = (flags & Constants.JBD2_FLAG_LAST_TAG) != 0;
            tags.add(new DescriptorTag(fsBlock, (int) flags,
                    (flags & Constants.JBD2_FLAG_ESCAPE) != 0,
                    (flags & Constants.JBD2_FLAG_SAME_UUID) != 0,
                    (flags & Constants.JBD2_FLAG_DELETED) != 0,
                    last, index));
            index++;
            if (last) break;
            at += tagSize;
        }
        return tags;
    }

    /** Lab revoke table: u32 count then that many big-endian u32 fs block numbers. */
    public static List<Long> parseRevokeEntries(byte[] b) {
        long count = be32(b, REV_COUNT_AT);
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int at = REV_ENTRIES_AT + i * 4;
            if (at + 4 <= b.length) out.add(be32(b, at));
        }
        return out;
    }

    public static long commitStoredChecksum(byte[] b) {
        return be32(b, COMMIT_CKSUM_AT);
    }

    /** Lab guard: CRC32 over descriptor + data blocks + commit header (12 bytes). */
    public static long computeCommitChecksum(byte[] descriptor, List<byte[]> dataBlocks, byte[] commitHeader12) {
        CRC32 crc = new CRC32();
        crc.update(descriptor);
        for (byte[] d : dataBlocks) crc.update(d);
        crc.update(commitHeader12);
        return crc.getValue();
    }

    // ------------------------------------------------------------------
    // Builders (used by the reproducible case-pack generator)
    // ------------------------------------------------------------------

    public static byte[] header(int type, long sequence) {
        byte[] h = new byte[12];
        put32(h, 0, Constants.JBD2_MAGIC);
        put32(h, 4, type);
        put32(h, 8, sequence);
        return h;
    }

    private static byte[] withUuid(byte[] head) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head);
        byte[] uuid = "fslab-jbd2-lab!!".getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(uuid);
        return out.toByteArray();
    }

    public static byte[] buildSuperblock(int blockSize, long sStart, long maxLen,
                                         int featureIncompat, int checksumType) {
        byte[] b = new byte[blockSize];
        put32(b, 0, Constants.JBD2_MAGIC);
        put32(b, 4, Constants.JBD2_SUPERBLOCK_V2);
        put32(b, 8, 0);
        put16(b, SB_FEATURE_COMPAT, 0);
        put16(b, SB_FEATURE_INCOMPAT, featureIncompat);
        put16(b, SB_FEATURE_RO_COMPAT, 0);
        put32(b, SB_BLOCKSIZE, blockSize);
        put32(b, SB_MAXLEN, maxLen);
        put64(b, SB_S_START, sStart);
        put32(b, SB_MAX_TXN, maxLen / 2);
        put32(b, SB_MAX_TXN_DATA, maxLen);
        put32(b, SB_NR_USERS, 1);
        b[SB_CKSUM_TYPE] = (byte) checksumType;
        long crc = crc32(Arrays.copyOfRange(b, 0, SB_CKSUM_REGION_END));
        put32(b, SB_CKSUM_AT, crc);
        return b;
    }

    /**
     * Descriptor block. tagBlocks[i] = fs block number; flags[i] carries the raw
     * flag set; LAST_TAG is forced on the final tag by the caller convention here.
     */
    public static byte[] buildDescriptor(long sequence, List<Long> fsBlocks, List<Integer> flagOverrides,
                                         boolean sixtyFourBit, int blockSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(withUuid(header(Constants.JBD2_DESCRIPTOR_BLOCK, sequence)));
        int tagSize = sixtyFourBit ? TAG_SIZE_64 : TAG_SIZE_32;
        for (int i = 0; i < fsBlocks.size(); i++) {
            long flags = flagOverrides == null ? 0 : (flagOverrides.get(i) == null ? 0 : flagOverrides.get(i));
            if (i == fsBlocks.size() - 1) flags |= Constants.JBD2_FLAG_LAST_TAG;
            byte[] tag = new byte[tagSize];
            put32(tag, 0, fsBlocks.get(i) & 0xFFFFFFFFL);
            put32(tag, 4, flags);
            if (sixtyFourBit) put32(tag, 8, fsBlocks.get(i) >>> 32);
            out.writeBytes(tag);
        }
        byte[] body = out.toByteArray();
        return padTo(body, blockSize);
    }

    public static byte[] buildRevoke(long sequence, List<Long> revokedFsBlocks, int blockSize) {
        byte[] b = new byte[blockSize];
        byte[] head = header(Constants.JBD2_REVOKE_BLOCK, sequence);
        System.arraycopy(head, 0, b, 0, 12);
        put32(b, REV_COUNT_AT, revokedFsBlocks.size());
        for (int i = 0; i < revokedFsBlocks.size(); i++) {
            put32(b, REV_ENTRIES_AT + i * 4, revokedFsBlocks.get(i));
        }
        return b;
    }

    /** Commit block with the lab CRC32 guard written into the 32-byte checksum area. */
    public static byte[] buildCommit(long sequence, byte[] descriptor, List<byte[]> dataBlocks,
                                     long commitSeconds, int blockSize) {
        byte[] b = new byte[blockSize];
        byte[] head = header(Constants.JBD2_COMMIT_BLOCK, sequence);
        System.arraycopy(head, 0, b, 0, 12);
        long crc = computeCommitChecksum(descriptor, dataBlocks, head);
        put32(b, COMMIT_CKSUM_AT, crc);
        put64(b, 44, commitSeconds);
        put32(b, 52, 0);
        return b;
    }

    public static byte[] buildData(long fsBlockTag, int fill, byte[] marker, int blockSize) {
        byte[] b = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) b[i] = (byte) fill;
        // A recognizable, block-unique payload so overwrite provenance is visible.
        put32(b, 0, 0x44415441L); // "DATA"
        put32(b, 4, fsBlockTag & 0xFFFFFFFFL);
        put32(b, 8, fill & 0xFFFFFFFFL);
        if (marker != null) System.arraycopy(marker, 0, b, 16, Math.min(marker.length, 32));
        return b;
    }

    public static byte[] padTo(byte[] body, int blockSize) {
        if (body.length == blockSize) return body;
        byte[] b = new byte[blockSize];
        System.arraycopy(body, 0, b, 0, Math.min(body.length, blockSize));
        return b;
    }

    // ------------------------------------------------------------------
    static long be32(byte[] b, int at) {
        return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16)
                | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
    }
    static int be16(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }
    static long be64(byte[] b, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[at + i] & 0xFFL);
        return v;
    }
    static void put32(byte[] b, int at, long v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
    static void put16(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 8);
        b[at + 1] = (byte) v;
    }
    static void put64(byte[] b, int at, long v) {
        for (int i = 8; i > 0; i--) {
            b[at + i - 1] = (byte) v;
            v >>>= 8;
        }
    }

    private static String bytesHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte value : b) {
            sb.append(Character.forDigit((value >> 4) & 0xF, 16));
            sb.append(Character.forDigit(value & 0xF, 16));
        }
        return sb.toString();
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
