package jbd2lab.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * On-disk format for the lab's JBD2-shaped journal image.
 *
 * <p>This is a documented, self-contained binary format that mirrors the semantic
 * layout of JBD2 journal blocks (journal_header_s + tags / revoke entries /
 * commit_header). It is not bit-for-bit kernel layout: every field is fixed-size
 * little-endian so snapshots can be built, mutated and diffed deterministically.
 *
 * <p>Shared block header (16 bytes) on every journal block:
 * <pre>
 *  off  size  field
 *  0    4     magic      ('jbd2-lab magic 0x4A424432 == "JBD2")
 *  4    4     blockType  1 descriptor, 2 commit, 3 revoke, 4 data
 *  8    4     sequence   transaction sequence number (uint32, wraps)
 *  12   4     flags      bit0 = checksum present/valid slot
 *  16   ...
 * </pre>
 *
 * <p>Descriptor block body: tags, each 16 bytes:
 * <pre>
 *  0 8  fsBlock   target filesystem block number (uint64)
 *  8 4  flags     bit0=ESCAPE (payload is itself metadata), bit1=SAME_UUID (n/a),
 *                 bit2=DELETED (tag present but data absent)
 *  12 4 tagCheck (reserved/0 in this format)
 * </pre>
 *
 * <p>Commit block body: 4-byte checksum at offset 16 (CRC32C over the bytes named
 * in {@link #transactionChecksum}); zero means "checksum absent".
 *
 * <p>Revoke block body: 4-byte count at 16, then count x uint32 revoked fs blocks.
 *
 * <p>Data blocks: the block is payload only (after the 16-byte header in the
 * journal image; payload length = blockSize - 16 in ring image storage).
 */
public final class JournalFormat {
    public static final int MAGIC = 0x4A424432; // "JBD2"

    public static final int TYPE_DESCRIPTOR = 1;
    public static final int TYPE_COMMIT = 2;
    public static final int TYPE_REVOKE = 3;
    public static final int TYPE_DATA = 4;

    public static final int HEADER_SIZE = 16;
    public static final int TAG_SIZE = 16;
    public static final int COMMIT_CSUM_OFF = 16;
    public static final int REVOKE_COUNT_OFF = 16;

    public static final int TAG_FLAG_ESCAPE = 0x1;
    public static final int TAG_FLAG_DELETED = 0x4;

    /** JBD2 checksum version carried by the journal superblock (0..3). */
    public static final int CSUM_V1 = 1;
    public static final int CSUM_V2 = 2;
    public static final int CSUM_V3 = 3;

    private JournalFormat() {
    }

    public static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static int u32(byte[] b, int off) {
        return le(b).getInt(off);
    }

    public static long u64(byte[] b, int off) {
        return le(b).getLong(off);
    }

    public static boolean hasMagic(byte[] block) {
        return block.length >= 4 && u32(block, 0) == MAGIC;
    }

    /** CRC32C over descriptor tags, revoke entries and every data payload, in ring order. */
    public static int transactionChecksum(List<byte[]> blocks) {
        int crc = 0xFFFFFFFF;
        for (byte[] blk : blocks) {
            int type = u32(blk, 4);
            if (type == TYPE_DESCRIPTOR) {
                crc = Crc32c.update(crc, blk, HEADER_SIZE, blk.length - HEADER_SIZE);
            } else if (type == TYPE_REVOKE) {
                crc = Crc32c.update(crc, blk, HEADER_SIZE, blk.length - HEADER_SIZE);
            } else if (type == TYPE_DATA) {
                crc = Crc32c.update(crc, blk, HEADER_SIZE, blk.length - HEADER_SIZE);
            }
        }
        return crc ^ 0xFFFFFFFF;
    }

    public record Tag(long fsBlock, int flags, int tagCheck) {
        public boolean escape() {
            return (flags & TAG_FLAG_ESCAPE) != 0;
        }

        public boolean deleted() {
            return (flags & TAG_FLAG_DELETED) != 0;
        }
    }

    public static List<Tag> parseTags(byte[] descriptorBlock) {
        List<Tag> tags = new ArrayList<>();
        ByteBuffer bb = le(descriptorBlock);
        for (int off = HEADER_SIZE; off + TAG_SIZE <= descriptorBlock.length; off += TAG_SIZE) {
            long fsBlock = bb.getLong(off);
            int flags = bb.getInt(off + 8);
            int check = bb.getInt(off + 12);
            if (fsBlock == 0 && flags == 0 && check == 0) {
                break; // zero padding terminator
            }
            tags.add(new Tag(fsBlock, flags, check));
        }
        return tags;
    }

    public static List<Long> parseRevoked(byte[] revokeBlock) {
        List<Long> blocks = new ArrayList<>();
        int count = u32(revokeBlock, REVOKE_COUNT_OFF);
        ByteBuffer bb = le(revokeBlock);
        int max = (revokeBlock.length - REVOKE_COUNT_OFF - 4) / 4;
        int n = Math.min(count, max);
        for (int i = 0; i < n; i++) {
            blocks.add(Integer.toUnsignedLong(bb.getInt(REVOKE_COUNT_OFF + 4 + i * 4)));
        }
        return blocks;
    }

    public static int commitChecksum(byte[] commitBlock) {
        if (commitBlock.length < COMMIT_CSUM_OFF + 4) {
            return 0;
        }
        return u32(commitBlock, COMMIT_CSUM_OFF);
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static byte[] unhex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    // -------- writers (used by fixture builder) --------

    public static byte[] header(int type, int sequence) {
        byte[] h = new byte[HEADER_SIZE];
        ByteBuffer bb = le(h);
        bb.putInt(0, MAGIC);
        bb.putInt(4, type);
        bb.putInt(8, sequence);
        bb.putInt(12, 0);
        return h;
    }

    public static byte[] descriptor(int sequence, List<Tag> tags, int blockSize) {
        byte[] blk = new byte[blockSize];
        ByteBuffer bb = le(blk);
        bb.putInt(0, MAGIC);
        bb.putInt(4, TYPE_DESCRIPTOR);
        bb.putInt(8, sequence);
        int off = HEADER_SIZE;
        for (Tag t : tags) {
            bb.putLong(off, t.fsBlock());
            bb.putInt(off + 8, t.flags());
            bb.putInt(off + 12, t.tagCheck());
            off += TAG_SIZE;
        }
        return blk;
    }

    public static byte[] revoke(int sequence, List<Long> blocks, int blockSize) {
        byte[] blk = new byte[blockSize];
        ByteBuffer bb = le(blk);
        bb.putInt(0, MAGIC);
        bb.putInt(4, TYPE_REVOKE);
        bb.putInt(8, sequence);
        bb.putInt(REVOKE_COUNT_OFF, blocks.size());
        int off = REVOKE_COUNT_OFF + 4;
        for (long fsBlock : blocks) {
            bb.putInt(off, (int) fsBlock);
            off += 4;
        }
        return blk;
    }

    public static byte[] data(int sequence, byte[] payload, int blockSize) {
        byte[] blk = new byte[blockSize];
        ByteBuffer bb = le(blk);
        bb.putInt(0, MAGIC);
        bb.putInt(4, TYPE_DATA);
        bb.putInt(8, sequence);
        System.arraycopy(payload, 0, blk, HEADER_SIZE,
                Math.min(payload.length, blockSize - HEADER_SIZE));
        return blk;
    }

    public static byte[] commit(int sequence, int checksum, int blockSize) {
        byte[] blk = new byte[blockSize];
        ByteBuffer bb = le(blk);
        bb.putInt(0, MAGIC);
        bb.putInt(4, TYPE_COMMIT);
        bb.putInt(8, sequence);
        bb.putInt(COMMIT_CSUM_OFF, checksum);
        return blk;
    }
}
