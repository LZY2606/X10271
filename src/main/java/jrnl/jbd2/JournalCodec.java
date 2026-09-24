package jrnl.jbd2;

import jrnl.domain.BlockHeader;
import jrnl.domain.ChecksumVersion;
import jrnl.domain.DescriptorTag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Big-endian codec for the fixture's on-disk JBD2 structures (layouts match the
 * kernel structures; field coverage is documented in the case-pack README).
 *
 * journal_header_s:  u32 h_magic (0xC03B3998), u32 h_blocktype, u32 h_sequence
 * journal_block_tag_s (64bit feature, 8 bytes):
 *                    u32 t_blocknr_low, u16 t_flags, u16 t_blocknr_high
 * commit header:     journal_header_s + u32 h_chksum_type? no — u32 commit seq
 * revoke entries:    u32 revoked fs block numbers
 */
public final class JournalCodec {

    public static final long MAGIC = 0xC03B3998L;
    public static final int HEADER_LEN = 12;
    public static final int TAG_LEN_64 = 8;
    public static final int TAG_LEN_32 = 4;

    private JournalCodec() {
    }

    static ByteBuffer be(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    }

    public static BlockHeader parseHeader(byte[] block) {
        if (block == null || block.length < HEADER_LEN) {
            return new BlockHeader(0, 0, 0, BlockHeader.typeName(0), "short block: no 12-byte header");
        }
        ByteBuffer buf = be(block);
        long magic = buf.getInt(0) & 0xFFFFFFFFL;
        int type = buf.getInt(4);
        long seq = buf.getInt(8) & 0xFFFFFFFFL;
        String diag = magic == MAGIC ? null
                : String.format("bad journal magic 0x%08x at this block", magic);
        return new BlockHeader(type, seq, type, BlockHeader.typeName(type), diag);
    }

    public static List<DescriptorTag> parseTags(byte[] descriptorBlock) {
        ByteBuffer buf = be(descriptorBlock);
        List<DescriptorTag> tags = new ArrayList<>();
        int off = HEADER_LEN;
        while (off + TAG_LEN_64 <= descriptorBlock.length - 4) {
            long low = buf.getInt(off) & 0xFFFFFFFFL;
            int flags = buf.getShort(off + 4) & 0xFFFF;
            int high = buf.getShort(off + 6) & 0xFFFF;
            long fsBlock = ((long) high << 32) | low;
            boolean sameUuid = (flags & DescriptorTag.FLAG_SAME_UUID) != 0;
            boolean deleted = (flags & DescriptorTag.FLAG_DELETED) != 0;
            tags.add(new DescriptorTag(fsBlock, flags, high != 0, sameUuid, deleted));
            off += TAG_LEN_64;
            if ((flags & DescriptorTag.FLAG_LAST_TAG) != 0) {
                break;
            }
        }
        return tags;
    }

    public static List<Long> parseRevokeEntries(byte[] revokeBlock) {
        ByteBuffer buf = be(revokeBlock);
        List<Long> entries = new ArrayList<>();
        int off = HEADER_LEN;
        while (off + 4 <= revokeBlock.length - 4) {
            long v = buf.getInt(off) & 0xFFFFFFFFL;
            if (v == 0) {
                break;
            }
            entries.add(v);
            off += 4;
        }
        return entries;
    }

    public static long commitSequence(byte[] commitBlock) {
        return be(commitBlock).getInt(HEADER_LEN) & 0xFFFFFFFFL;
    }

    public static long tailChecksum(byte[] block) {
        return be(block).getInt(block.length - 4) & 0xFFFFFFFFL;
    }

    public static Boolean verifyDescriptor(byte[] block, ChecksumVersion version) {
        if (version == ChecksumVersion.NONE) {
            return Boolean.TRUE;
        }
        long expected = tailChecksum(block);
        long actual = Checksums.compute(version, Checksums.descriptorCoverage(block));
        return actual == expected;
    }

    public static Boolean verifyCommit(byte[] block, ChecksumVersion version) {
        if (version == ChecksumVersion.NONE) {
            return Boolean.TRUE;
        }
        long expected = tailChecksum(block);
        long actual = Checksums.compute(version, Checksums.commitCoverage(block));
        return actual == expected;
    }

    /** Minimal journal superblock v2 fields preserved for cross-check diagnostics. */
    public record JournalSuperblock(long blockSize, long maxLen, long first, long sequence, String diagnostic) {
    }

    public static JournalSuperblock parseSuperblock(byte[] block) {
        if (block == null || block.length < 32) {
            return new JournalSuperblock(0, 0, 0, 0, "short journal superblock");
        }
        ByteBuffer buf = be(block);
        long magic = buf.getInt(0) & 0xFFFFFFFFL;
        if (magic != MAGIC) {
            return new JournalSuperblock(0, 0, 0, 0, String.format("superblock magic 0x%08x", magic));
        }
        return new JournalSuperblock(
                buf.getInt(12) & 0xFFFFFFFFL,
                buf.getInt(20) & 0xFFFFFFFFL,
                buf.getInt(24) & 0xFFFFFFFFL,
                buf.getInt(28) & 0xFFFFFFFFL,
                null);
    }
}
