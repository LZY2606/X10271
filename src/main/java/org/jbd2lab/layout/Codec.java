package org.jbd2lab.layout;

import org.jbd2lab.util.Be;
import org.jbd2lab.util.Checksums;

/**
 * Encoders and checksum logic for JBD2-LAB blocks.
 *
 * <p>Checksum rules by superblock version:
 * <ul>
 *   <li>v0: no checksums anywhere;</li>
 *   <li>v1: descriptor/revoke/data carry a CRC32C in their final 4 bytes; commit block carries
 *       the transaction descriptor-block CRC at offset 12;</li>
 *   <li>v2: every descriptor tag carries its own CRC32C tail (over the 14 base tag bytes,
 *       big-endian sequence of the owning transaction) plus block-tail CRCs as in v1.</li>
 * </ul>
 */
public final class Codec {
    private Codec() {}

    private static void header(byte[] b, int type, long seq) {
        Be.putU32(b, 0, Jbd2.SUPERBLOCK_MAGIC);
        Be.putU32(b, 4, type);
        Be.putU32(b, 8, seq);
    }

    public static byte[] superblock(int blockSize, int checksumVersion, long first, long startSeq,
                                    long maxLen) {
        byte[] b = new byte[Jbd2.SB_SIZE];
        Be.putU32(b, 0, Jbd2.SUPERBLOCK_MAGIC);
        Be.putU32(b, 4, Jbd2.SUPERBLOCK_V2);
        Be.putU16(b, 8, blockSize);
        int incompat = Jbd2.INCOMPAT_FEATURE_64BLOCK;
        if (checksumVersion >= Jbd2.CSUM_VERSION_1) {
            incompat |= Jbd2.INCOMPAT_FEATURE_CSUM;
        }
        if (checksumVersion >= Jbd2.CSUM_VERSION_2) {
            incompat |= Jbd2.INCOMPAT_FEATURE_CSUM_V3;
        }
        Be.putU32(b, 12, incompat);
        Be.putU32(b, 24, first);
        Be.putU32(b, 28, startSeq);
        Be.putU32(b, 36, maxLen);
        b[40] = (byte) checksumVersion;
        return b;
    }

    public static byte[] descriptor(int blockSize, long seq, long[] blocks, int[] flags,
                                    int checksumVersion) {
        byte[] b = new byte[blockSize];
        header(b, Jbd2.DESCRIPTOR_BLOCK, seq);
        int tagSize = checksumVersion >= Jbd2.CSUM_VERSION_2 ? Jbd2.TAG_SIZE_CSUM : Jbd2.TAG_SIZE_BASE;
        int off = Jbd2.HEADER_SIZE;
        for (int i = 0; i < blocks.length; i++) {
            Be.putU32(b, off, blocks[i] & 0xFFFFFFFFL);
            int fl = flags[i] | Jbd2.TAG_FLAG_SAME_UUID | Jbd2.TAG_FLAG_64BIT;
            if (i == blocks.length - 1) {
                fl |= Jbd2.TAG_FLAG_LAST_TAG;
            }
            Be.putU16(b, off + 8, fl);
            Be.putU32(b, off + 10, (blocks[i] >>> 32) & 0xFFFFFFFFL);
            if (checksumVersion >= Jbd2.CSUM_VERSION_2) {
                long c = Checksums.crc32c(tagCrcInput(b, off, seq));
                Be.putU32(b, off + 14, c);
            }
            off += tagSize;
        }
        if (checksumVersion == Jbd2.CSUM_VERSION_1) {
            Be.putU32(b, blockSize - 4, Checksums.crc32c(b, 0, blockSize - 4));
        }
        return b;
    }

    private static byte[] tagCrcInput(byte[] block, int tagOff, long seq) {
        byte[] in = new byte[Jbd2.TAG_SIZE_BASE + 4];
        System.arraycopy(block, tagOff, in, 0, Jbd2.TAG_SIZE_BASE);
        Be.putU32(in, Jbd2.TAG_SIZE_BASE, seq);
        return in;
    }

    /** Data block: payload, optional trailing CRC32C over payload bytes. */
    public static byte[] dataBlock(int blockSize, byte[] payload, int checksumVersion) {
        byte[] b = new byte[blockSize];
        int n = Math.min(payload.length, blockSize - (checksumVersion >= 1 ? 4 : 0));
        System.arraycopy(payload, 0, b, 0, n);
        if (checksumVersion >= Jbd2.CSUM_VERSION_1) {
            Be.putU32(b, blockSize - 4, Checksums.crc32c(b, 0, blockSize - 4));
        }
        return b;
    }

    public static byte[] commitBlock(int blockSize, long seq, long descriptorCrc, int checksumVersion) {
        byte[] b = new byte[blockSize];
        header(b, Jbd2.COMMIT_BLOCK, seq);
        Be.putU32(b, 12, descriptorCrc);
        if (checksumVersion >= Jbd2.CSUM_VERSION_1) {
            Be.putU32(b, blockSize - 4, Checksums.crc32c(b, 0, blockSize - 4));
        }
        return b;
    }

    public static byte[] revokeBlock(int blockSize, long seq, long[] fsBlocks, int checksumVersion) {
        byte[] b = new byte[blockSize];
        header(b, Jbd2.REVOKE_BLOCK, seq);
        Be.putU32(b, 12, fsBlocks.length);
        int off = 16;
        for (long fs : fsBlocks) {
            Be.putU64(b, off, fs);
            off += 8;
        }
        if (checksumVersion >= Jbd2.CSUM_VERSION_1) {
            Be.putU32(b, blockSize - 4, Checksums.crc32c(b, 0, blockSize - 4));
        }
        return b;
    }

    /** Fluent corruption helpers used by counterexample fixtures. */
    public static byte[] withByteFlipped(byte[] block, int offset) {
        byte[] copy = block.clone();
        copy[offset] ^= 1;
        return copy;
    }

    public static byte[] withTailCrcCorrupted(byte[] block) {
        byte[] copy = block.clone();
        Be.putU32(copy, copy.length - 4, Be.u32(copy, copy.length - 4) ^ 0xDEADBEEFL);
        return copy;
    }
}
