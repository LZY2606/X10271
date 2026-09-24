package jrnl.fixture;

import jrnl.domain.ChecksumVersion;
import jrnl.domain.DescriptorTag;
import jrnl.jbd2.Checksum;

import java.util.HexFormat;

/**
 * Builds on-disk JBD2 blocks for the offline case pack. Layouts are the real
 * kernel structures (big endian); payload blocks carry an ASCII-readable marker
 * and zero fill so reviewers can read original bytes directly.
 */
public final class BlockImage {

    public static final long MAGIC = 0xC03B3998L;
    public static final int DESCRIPTOR = 1;
    public static final int COMMIT = 2;
    public static final int SUPERBLOCK_V2 = 3;
    public static final int REVOKE = 4;

    private BlockImage() {
    }

    public static byte[] header(byte[] block, int type, long seq) {
        putInt(block, 0, MAGIC);
        putInt(block, 4, type);
        putInt(block, 8, (int) seq);
        return block;
    }

    public static byte[] descriptor(int blockSize, long seq, TagSpec[] tags, ChecksumVersion cv) {
        byte[] b = zero(blockSize);
        header(b, DESCRIPTOR, seq);
        int off = 12;
        for (int i = 0; i < tags.length; i++) {
            TagSpec t = tags[i];
            int flags = t.flags();
            if (i == tags.length - 1) {
                flags |= DescriptorTag.FLAG_LAST_TAG;
            }
            putInt(b, off, (int) t.fsBlock());
            putShort(b, off + 4, (short) flags);
            putShort(b, off + 6, (short) (t.fsBlock() >>> 32));
            off += 8;
        }
        if (cv != ChecksumVersion.NONE) {
            putChecksum(b, Checksums.INSTANCE.compute(cv, Checksums.INSTANCE.descriptorCoverage(b)));
        }
        return b;
    }

    public static byte[] commit(int blockSize, long seq, ChecksumVersion cv, boolean corrupt) {
        byte[] b = zero(blockSize);
        header(b, COMMIT, seq);
        putInt(b, 12, (int) seq);
        if (cv != ChecksumVersion.NONE) {
            long sum = Checksums.INSTANCE.compute(cv, Checksums.INSTANCE.commitCoverage(b));
            if (corrupt) {
                sum ^= 0xDEADBEEFL;
            }
            putChecksum(b, sum);
        }
        return b;
    }

    public static byte[] revoke(int blockSize, long seq, long[] fsBlocks) {
        byte[] b = zero(blockSize);
        header(b, REVOKE, seq);
        int off = 12;
        for (long fs : fsBlocks) {
            putInt(b, off, (int) fs);
            off += 4;
        }
        return b;
    }

    public static byte[] superblockV2(int blockSize, long maxLen, long first, long seq) {
        byte[] b = zero(blockSize);
        putInt(b, 0, MAGIC);
        putInt(b, 4, blockSize);
        putInt(b, 20, (int) maxLen);
        putInt(b, 24, (int) first);
        putInt(b, 28, (int) seq);
        return b;
    }

    /** Data block: readable ASCII marker followed by zero fill. */
    public static byte[] data(int blockSize, String marker) {
        byte[] b = zero(blockSize);
        byte[] m = marker.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(m, 0, b, 0, Math.min(m.length, blockSize - 1));
        return b;
    }

    /** Flip one byte near the checksum field (but not the field itself) to break a commit. */
    public static byte[] corruptPayload(byte[] block) {
        byte[] c = block.clone();
        c[16] ^= 0xFF;
        return c;
    }

    private static void putChecksum(byte[] b, long sum) {
        putInt(b, b.length - 4, (int) sum);
    }

    static byte[] zero(int n) {
        return new byte[n];
    }

    static void putInt(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static void putShort(byte[] b, int off, short v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    public record TagSpec(long fsBlock, int flags) {
        public static TagSpec of(long fsBlock) {
            return new TagSpec(fsBlock, 0);
        }

        public static TagSpec deleted(long fsBlock) {
            return new TagSpec(fsBlock, DescriptorTag.FLAG_DELETED);
        }
    }
}
