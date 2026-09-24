package lab.jbd2.binary;

import lab.jbd2.model.BlockKind;
import lab.jbd2.model.ChecksumVersion;

/**
 * On-disk layout of the self-contained lab journal image. Field placement follows the
 * real JBD2 journal block header / tag / commit / revoke shapes so the fixture is real
 * domain material rather than a generic table.
 *
 * <p>Common block header (big-endian, 12 bytes):
 * magic(0xC03B3998) | blocktype | sequence(u32).
 */
public final class Jbd2Format {

    public static final long MAGIC = 0xC03B3998L;

    public static final int HEADER_LEN = 12;
    public static final int TAG_BASE_LEN = 8;   // blocknr(u32) + flags(u32)
    public static final int TAG_CSUM_LEN = 4;   // appended when checksums enabled

    public static final int SB_OFF_BLOCKSIZE = 0x58;
    public static final int SB_OFF_MAX_BLOCKS = 0x5C;
    public static final int SB_OFF_RING_START = 0x60;
    public static final int SB_OFF_FIRST_SEQ = 0x64;
    public static final int SB_OFF_CRASH_TAIL = 0x68;
    public static final int SB_OFF_CHECKSUM_TYPE = 0x6C;

    public static final int COMMIT_OFF_CHECKSUM = 0x18;
    public static final int REVOKE_OFF_COUNT = 0x0C;
    public static final int REVOKE_OFF_TABLE = 0x10;

    public static final int FLAG_ESCAPE = 0x1;
    public static final int FLAG_SAME_UUID = 0x2;
    public static final int FLAG_DELETED = 0x4;
    public static final int FLAG_LAST_TAG = 0x8;

    private Jbd2Format() {
    }

    public static byte[] newBlock(int blockSize, byte fill) {
        byte[] b = new byte[blockSize];
        java.util.Arrays.fill(b, fill);
        return b;
    }

    public static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static long getU32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24)
                | ((long) (b[off + 1] & 0xff) << 16)
                | ((long) (b[off + 2] & 0xff) << 8)
                | (b[off + 3] & 0xff);
    }

    public static void writeHeader(byte[] block, BlockKind kind, long sequence) {
        putU32(block, 0, MAGIC);
        putU32(block, 4, kind.code);
        putU32(block, 8, sequence & 0xffffffffL);
    }

    public static boolean magicOk(byte[] block) {
        return block != null && block.length >= HEADER_LEN && getU32(block, 0) == MAGIC;
    }

    public static BlockKind kindOf(byte[] block) {
        return BlockKind.fromCode((int) getU32(block, 4));
    }

    public static long sequenceOf(byte[] block) {
        return getU32(block, 8);
    }

    /** Builds a superblock carrying the lab parameters (with the JBD2 magic prefix). */
    public static byte[] writeSuperblock(int blockSize, long maxBlocks, long ringStart,
                                         long firstSeq, long crashTail, int checksumType) {
        byte[] b = newBlock(blockSize, (byte) 0);
        putU32(b, 0, MAGIC);
        putU32(b, 4, BlockKind.SUPERBLOCK.code);
        putU32(b, SB_OFF_BLOCKSIZE, blockSize);
        putU32(b, SB_OFF_MAX_BLOCKS, maxBlocks);
        putU32(b, SB_OFF_RING_START, ringStart);
        putU32(b, SB_OFF_FIRST_SEQ, firstSeq & 0xffffffffL);
        putU32(b, SB_OFF_CRASH_TAIL, crashTail);
        putU32(b, SB_OFF_CHECKSUM_TYPE, checksumType);
        return b;
    }

    public record SuperInfo(long blockSize, long maxBlocks, long ringStart, long firstSeq,
                            long crashTail, int checksumType) {
        public ChecksumVersion checksumVersion() {
            return ChecksumVersion.fromCode(checksumType);
        }
    }

    public static SuperInfo readSuperblock(byte[] b) {
        return new SuperInfo(getU32(b, SB_OFF_BLOCKSIZE), getU32(b, SB_OFF_MAX_BLOCKS),
                getU32(b, SB_OFF_RING_START), getU32(b, SB_OFF_FIRST_SEQ),
                getU32(b, SB_OFF_CRASH_TAIL), (int) getU32(b, SB_OFF_CHECKSUM_TYPE));
    }

    /**
     * Writes one descriptor tag at {@code off}; returns the new offset. The tag checksum
     * field (v1/v2) covers the preceding 8 tag bytes with the journal uuid seed of zero.
     */
    public static int writeTag(byte[] desc, int off, long fsBlock, int flags, int checksumType) {
        putU32(desc, off, fsBlock);
        putU32(desc, off + 4, flags);
        if (checksumType != 0) {
            long c = Checksums.checksum(checksumType, desc, off, TAG_BASE_LEN);
            putU32(desc, off + TAG_BASE_LEN, c);
            return off + TAG_BASE_LEN + TAG_CSUM_LEN;
        }
        return off + TAG_BASE_LEN;
    }

    public record TagRead(long fsBlock, int flags, boolean checksumOk, int nextOff) {
        public boolean last() {
            return (flags & FLAG_LAST_TAG) != 0;
        }
    }

    public static TagRead readTag(byte[] desc, int off, int checksumType) {
        long fsBlock = getU32(desc, off);
        int flags = (int) getU32(desc, off + 4);
        int len = TAG_BASE_LEN;
        boolean csumOk = true;
        if (checksumType != 0) {
            long expect = Checksums.checksum(checksumType, desc, off, TAG_BASE_LEN);
            csumOk = expect == getU32(desc, off + TAG_BASE_LEN);
            len += TAG_CSUM_LEN;
        }
        return new TagRead(fsBlock, flags, csumOk, off + len);
    }

    public static int tagStride(int checksumType) {
        return checksumType == 0 ? TAG_BASE_LEN : TAG_BASE_LEN + TAG_CSUM_LEN;
    }

    /** Finalises a descriptor block: checksum (v1/v2) covers header + all tag bytes. */
    public static void sealDescriptor(byte[] desc, int tagBytes, int checksumType) {
        if (checksumType == 0) {
            return;
        }
        long c = Checksums.checksum(checksumType, desc, 0, HEADER_LEN + tagBytes);
        putU32(desc, desc.length - 4, c);
    }

    public static boolean descriptorChecksumOk(byte[] desc, int tagBytes, int checksumType) {
        if (checksumType == 0) {
            return true;
        }
        return Checksums.checksum(checksumType, desc, 0, HEADER_LEN + tagBytes)
                == getU32(desc, desc.length - 4);
    }

    /** Commit checksum covers the 32-byte commit header prefix (zeroed checksum field). */
    public static void sealCommit(byte[] commit, int checksumType) {
        if (checksumType == 0) {
            return;
        }
        putU32(commit, COMMIT_OFF_CHECKSUM, 0);
        long c = Checksums.checksum(checksumType, commit, 0, 0x1C);
        putU32(commit, COMMIT_OFF_CHECKSUM, c);
    }

    public static boolean commitChecksumOk(byte[] commit, int checksumType) {
        if (checksumType == 0) {
            return true;
        }
        long stored = getU32(commit, COMMIT_OFF_CHECKSUM);
        putU32(commit, COMMIT_OFF_CHECKSUM, 0);
        long calc = Checksums.checksum(checksumType, commit, 0, 0x1C);
        putU32(commit, COMMIT_OFF_CHECKSUM, stored);
        return stored == calc;
    }

    /** Revoke table checksum (v2) covers the full block except the trailing u32. */
    public static void sealRevoke(byte[] revoke, int checksumType) {
        if (checksumType == 0) {
            return;
        }
        long c = Checksums.checksum(checksumType, revoke, 0, revoke.length - 4);
        putU32(revoke, revoke.length - 4, c);
    }

    public static boolean revokeChecksumOk(byte[] revoke, int checksumType) {
        if (checksumType == 0) {
            return true;
        }
        return Checksums.checksum(checksumType, revoke, 0, revoke.length - 4)
                == getU32(revoke, revoke.length - 4);
    }
}
