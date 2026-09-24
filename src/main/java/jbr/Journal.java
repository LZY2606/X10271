package jbr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JBD2 journal image: raw block bytes plus superblock parameters.
 * All domain quantities keep their raw representation and unit/coordinate base;
 * normalization happens only in the derived layer (see DerivedEngine).
 */
public final class Journal {
    public static final int MAGIC = 0xC03B3998;
    public static final int BT_DESCRIPTOR = 1;
    public static final int BT_COMMIT = 2;
    public static final int BT_SUPER_V1 = 3;
    public static final int BT_SUPER_V2 = 4;
    public static final int BT_REVOKE = 5;

    public static final int FLAG_ESCAPE = 1;
    public static final int FLAG_SAME_UUID = 2;
    public static final int FLAG_DELETED = 4;
    public static final int FLAG_LAST_TAG = 8;

    public final List<byte[]> blocks;       // raw journal blocks, index = journal-block-index
    public final int blockSize;             // normalized, bytes (invalid -> -1, see diagnostic)
    public final int ringStart;             // journal-block-index of first transaction head
    public final long baseSequence;         // u32 sequence of first transaction
    public final int checksumVersion;       // 2 or 3
    public final byte[] uuid;               // 16 bytes

    public Journal(List<byte[]> blocks, int blockSize, int ringStart, long baseSequence,
                   int checksumVersion, byte[] uuid) {
        this.blocks = blocks;
        this.blockSize = blockSize;
        this.ringStart = ringStart;
        this.baseSequence = baseSequence;
        this.checksumVersion = checksumVersion;
        this.uuid = uuid;
    }

    public int numBlocks() { return blocks.size(); }

    /** Block size normalization: must be a power of two within [1024, 65536]. */
    public static int normalizeBlockSize(long raw) {
        if (raw < 1024 || raw > 65536 || (raw & (raw - 1)) != 0) return -1;
        return (int) raw;
    }

    public static final class Header {
        public final int blockType;
        public final long sequence; // u32, may have wrapped
        public Header(int blockType, long sequence) {
            this.blockType = blockType;
            this.sequence = sequence;
        }
    }

    /** @return header or null if the magic is not a JBD2 magic. */
    public static Header parseHeader(byte[] block) {
        if (block.length < 12) return null;
        ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt(0) != MAGIC) return null;
        return new Header(b.getInt(4), b.getInt(8) & 0xFFFFFFFFL);
    }

    /** One descriptor tag: 8 bytes (blocknr u32, tag-checksum u16, flags u16). */
    public static final class Tag {
        public final long fsBlock;
        public final int flags;
        public Tag(long fsBlock, int flags) {
            this.fsBlock = fsBlock;
            this.flags = flags;
        }
        public boolean isLast() { return (flags & FLAG_LAST_TAG) != 0; }
        public boolean isDeleted() { return (flags & FLAG_DELETED) != 0; }
    }

    public static List<Tag> parseTags(byte[] descriptorBlock) {
        List<Tag> tags = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(descriptorBlock).order(ByteOrder.BIG_ENDIAN);
        int off = 12;
        while (off + 8 <= descriptorBlock.length - 4) {
            long fsBlock = b.getInt(off) & 0xFFFFFFFFL;
            int flags = b.getShort(off + 6) & 0xFFFF;
            Tag t = new Tag(fsBlock, flags);
            tags.add(t);
            off += 8;
            if (t.isLast()) break;
        }
        return tags;
    }

    public static List<Long> parseRevokeList(byte[] revokeBlock) {
        List<Long> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(revokeBlock).order(ByteOrder.BIG_ENDIAN);
        int count = b.getInt(12);
        for (int i = 0; i < count; i++) {
            int off = 16 + i * 4;
            if (off + 4 > revokeBlock.length) break;
            out.add(b.getInt(off) & 0xFFFFFFFFL);
        }
        return out;
    }

    /**
     * Checksum scheme (documented subset of JBD2 v2/v3):
     * seed = crc32c(uuid) then sequence (BE u32) folded in; then the block bytes
     * with the checksum field zeroed. v3 covers descriptor (tail 4 bytes) and
     * commit (bytes 16..20); v2 covers commit only.
     */
    public static int checksum(byte[] uuid, long sequence, byte[] block, int zeroFrom, int zeroTo) {
        byte[] copy = Arrays.copyOf(block, block.length);
        Arrays.fill(copy, zeroFrom, zeroTo, (byte) 0);
        int crc = Crc32c.compute(uuid);
        ByteBuffer seq = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) sequence);
        crc = Crc32c.compute(crc, seq.array(), 0, 4);
        return Crc32c.compute(crc, copy, 0, copy.length);
    }

    /** Verify a descriptor (v3: tail u32) or commit (v2/v3: bytes 16..20) checksum. */
    public boolean verifyChecksum(int blockType, long sequence, byte[] block) {
        ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
        if (blockType == BT_DESCRIPTOR) {
            if (checksumVersion < 3) return true;
            int stored = b.getInt(block.length - 4);
            return stored == checksum(uuid, sequence, block, block.length - 4, block.length);
        }
        if (blockType == BT_COMMIT) {
            int stored = b.getInt(16);
            return stored == checksum(uuid, sequence, block, 16, 20);
        }
        return true;
    }

    /** Builder used by fixtures and tests to construct well-formed journals. */
    public static final class Builder {
        private final int blockSize;
        private final byte[] uuid;

        public Builder(int blockSize, byte[] uuid) {
            this.blockSize = blockSize;
            this.uuid = uuid;
        }

        private static void header(byte[] block, int type, long seq) {
            ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
            b.putInt(0, MAGIC);
            b.putInt(4, type);
            b.putInt(8, (int) seq);
        }

        public byte[] superblockV2() {
            byte[] block = new byte[blockSize];
            header(block, BT_SUPER_V2, 0);
            ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
            b.putInt(12, blockSize); // s_blocksize
            b.putInt(16, 24);        // s_maxlen
            b.putInt(20, 2);         // s_first (ring start)
            b.putInt(24, 0);         // s_sequence patched by caller if needed
            return block;
        }

        public byte[] zeros() { return new byte[blockSize]; }

        public byte[] descriptor(long seq, long... fsBlocks) {
            byte[] block = new byte[blockSize];
            header(block, BT_DESCRIPTOR, seq);
            ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
            int off = 12;
            for (int i = 0; i < fsBlocks.length; i++) {
                b.putInt(off, (int) fsBlocks[i]);
                b.putShort(off + 4, (short) 0); // tag checksum (unused in this subset)
                int flags = FLAG_SAME_UUID | (i == fsBlocks.length - 1 ? FLAG_LAST_TAG : 0);
                b.putShort(off + 6, (short) flags);
                off += 8;
            }
            int crc = checksum(uuid, seq, block, blockSize - 4, blockSize);
            b.putInt(blockSize - 4, crc);
            return block;
        }

        public byte[] data(long fsBlock, int fillSeed) {
            byte[] block = new byte[blockSize];
            block[0] = (byte) 0xDA; block[1] = (byte) 0x7A;
            ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
            b.putInt(4, (int) fsBlock);
            b.putInt(8, fillSeed);
            for (int i = 12; i < blockSize; i++) block[i] = (byte) (fillSeed * 31 + i);
            return block;
        }

        public byte[] commit(long seq) {
            byte[] block = new byte[blockSize];
            header(block, BT_COMMIT, seq);
            ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
            b.put(12, (byte) 1);  // h_chksum_type = crc32c
            b.put(13, (byte) 4);  // h_chksum_size
            int crc = checksum(uuid, seq, block, 16, 20);
            b.putInt(16, crc);
            return block;
        }

        public byte[] revoke(long seq, long... fsBlocks) {
            byte[] block = new byte[blockSize];
            header(block, BT_REVOKE, seq);
            ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
            b.putInt(12, fsBlocks.length);
            for (int i = 0; i < fsBlocks.length; i++) b.putInt(16 + i * 4, (int) fsBlocks[i]);
            return block;
        }
    }
}
