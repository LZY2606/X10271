package org.journalreplay.jbd2;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a raw journal region into typed blocks.
 *
 * <p>On-disk layout (big-endian, offsets in bytes):
 * <ul>
 *   <li>header (12): magic 0xC03B3998 (4), blocktype (4), sequence (4)</li>
 *   <li>descriptor tags from 12, 16 bytes each: fsBlock (4), tagCrc32c (4),
 *       flags (2, bit0 = LAST), tagSize (2), reserved (4)</li>
 *   <li>commit: chksumType (1) at 12, chksumSize (1) at 13, pad (2),
 *       checksum (4) at 16 — v1 stores crc16 in the low 2 bytes</li>
 *   <li>revoke: r_count (4) at 12, then 4-byte fs block numbers from 16</li>
 *   <li>journal superblock v2: s_blocksize (4) at 12, s_maxlen (4) at 16,
 *       s_first (4) at 20, s_sequence (4) at 24, s_start (4) at 28,
 *       uuid (16) at 32, checksum version (4) at 48</li>
 * </ul>
 */
public final class JournalParser {
    public static final long MAGIC = 0xC03B3998L;

    private JournalParser() {}

    public record ParsedJournal(
            int blockSize,
            long logStartBlock,
            long firstSequence,
            byte[] uuid,
            Integer checksumVersionCode,
            List<JournalBlock> blocks,
            byte[] image) {}

    public static ParsedJournal parse(byte[] image, int blockSize, long logStartBlock) {
        int count = image.length / blockSize;
        List<JournalBlock> blocks = new ArrayList<>();
        long firstSeq = 0;
        byte[] uuid = new byte[16];
        Integer csumVersion = null;
        for (int i = 0; i < count; i++) {
            int off = i * blockSize;
            long magic = u32(image, off);
            if (magic != MAGIC) {
                // no JBD2 header: a data block (position assigned by descriptor tags)
                byte[] payload = new byte[blockSize];
                System.arraycopy(image, off, payload, 0, blockSize);
                blocks.add(new JournalBlock(i, logStartBlock + i, -1, BlockType.DATA,
                        List.of(), List.of(), payload, JournalBlock.ChecksumStatus.NOT_APPLICABLE,
                        hex(image, off, 12)));
                continue;
            }
            int typeCode = (int) u32(image, off + 4);
            long seq = u32(image, off + 8);
            BlockType type = BlockType.fromCode(typeCode);
            List<Long> fsBlocks = List.of();
            List<Long> revoked = List.of();
            if (type == BlockType.DESCRIPTOR) {
                fsBlocks = parseTags(image, off);
            } else if (type == BlockType.REVOKE) {
                revoked = parseRevoke(image, off);
            } else if (type == BlockType.SUPERBLOCK_V2 || type == BlockType.SUPERBLOCK_V1) {
                firstSeq = u32(image, off + 24);
                System.arraycopy(image, off + 32, uuid, 0, 16);
                csumVersion = (int) u32(image, off + 48);
            }
            blocks.add(new JournalBlock(i, logStartBlock + i, seq, type,
                    fsBlocks, revoked, null, JournalBlock.ChecksumStatus.NOT_APPLICABLE,
                    hex(image, off, 12)));
        }
        return new ParsedJournal(blockSize, logStartBlock, firstSeq, uuid, csumVersion, blocks, image);
    }

    private static List<Long> parseTags(byte[] image, int off) {
        List<Long> out = new ArrayList<>();
        int p = off + 12;
        while (p + 16 <= off + 1024 * 64 && p + 16 <= image.length) {
            long fsBlock = u32(image, p);
            int flags = u16(image, p + 8);
            if (fsBlock == 0 && flags == 0) break; // padding
            out.add(fsBlock);
            if ((flags & 1) != 0) break; // LAST
            p += 16;
        }
        return out;
    }

    private static List<Long> parseRevoke(byte[] image, int off) {
        List<Long> out = new ArrayList<>();
        long count = u32(image, off + 12);
        for (int i = 0; i < count; i++) {
            out.add(u32(image, off + 16 + i * 4));
        }
        return out;
    }

    public static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    public static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && off + i < b.length; i++) {
            sb.append(String.format("%02x", b[off + i]));
        }
        return sb.toString();
    }
}
