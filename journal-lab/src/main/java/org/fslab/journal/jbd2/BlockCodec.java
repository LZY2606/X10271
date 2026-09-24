package org.fslab.journal.jbd2;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encode/decode the laboratory JBD2 journal blocks.
 *
 * <h3>Layout of one journal block</h3>
 * All non-superblock blocks begin with the 12 byte header
 * {@code magic, blocktype, sequence} (big endian).
 *
 * <h3>Descriptor block</h3>
 * Tags follow immediately. A tag is {@code blocknr(8) flags(4)} (large tag form,
 * 16 bytes with reserved space) followed by a 4 byte per-block checksum when
 * checksum version 3 is active. Trailing 4 bytes of the block hold the
 * transaction-wide checksum for v2/v3.
 *
 * <h3>Commit block</h3>
 * Header followed by {@code checksumType(1), totalChecksum(4)} at offset 12.
 *
 * <h3>Revoke block</h3>
 * Header, then a 16 byte revoked-UUID slot, 4 byte count, then count * u64
 * revoked block numbers.
 *
 * <h3>Data blocks</h3>
 * Payload blocks have no journal header. If a payload byte sequence itself
 * starts with the JBD2 magic it is written through an escape block: a block of
 * type descriptor with the {@code ESCAPE} flag bit and the original payload in
 * the body.
 */
public final class BlockCodec {

    private BlockCodec() {
    }

    public record Header(int magic, int type, long sequence) {
    }

    public record Tag(long fsBlock, int flags, Integer blockChecksum) {
        public boolean escape() {
            return (flags & Jbd2.TAG_FLAG_ESCAPE) != 0;
        }

        public boolean last() {
            return (flags & Jbd2.TAG_FLAG_LAST_TAG) != 0;
        }
    }

    public record Descriptor(List<Tag> tags, Integer totalChecksum) {
    }

    public record Commit(int checksumType, int totalChecksum) {
    }

    public record Revoke(List<Long> fsBlocks) {
    }

    public static Header readHeader(byte[] block) {
        if (block.length < Jbd2.HEADER_SIZE) {
            return new Header(0, 0, 0);
        }
        int magic = (int) Jbd2.get32(block, 0);
        int type = (int) Jbd2.get32(block, 4);
        long seq = Jbd2.get32(block, 8);
        return new Header(magic, type, seq);
    }

    public static void writeHeader(byte[] block, int type, long sequence) {
        Jbd2.put32(block, 0, Jbd2.HEADER_MAGIC);
        Jbd2.put32(block, 4, type);
        Jbd2.put32(block, 8, sequence);
    }

    public static byte[] writeDescriptor(long sequence, List<Tag> tags,
                                         Integer totalChecksum, int checksumVersion, int blockSize) {
        byte[] block = new byte[blockSize];
        writeHeader(block, Jbd2.TYPE_DESC_BLOCK, sequence);
        int off = Jbd2.HEADER_SIZE;
        for (Tag tag : tags) {
            Jbd2.put32(block, off, tag.fsBlock >>> 32);
            Jbd2.put32(block, off + 4, tag.fsBlock & 0xffffffffL);
            int flags = tag.flags;
            if (checksumVersion == Jbd2.CHECKSUM_V3_BLOCK) {
                flags |= Jbd2.TAG_FLAG_LARGE_TAG;
            }
            Jbd2.put32(block, off + 8, flags);
            off += 12;
            if (checksumVersion == Jbd2.CHECKSUM_V3_BLOCK) {
                Jbd2.put32(block, off, tag.blockChecksum == null ? 0 : tag.blockChecksum);
                off += 4;
            }
        }
        if (checksumVersion != Jbd2.CHECKSUM_V0_NONE && totalChecksum != null) {
            Jbd2.put32(block, blockSize - 4, totalChecksum);
        }
        return block;
    }

    public static Descriptor readDescriptor(byte[] block, int checksumVersion) {
        List<Tag> tags = new ArrayList<>();
        int off = Jbd2.HEADER_SIZE;
        Integer total = null;
        while (off + 12 <= block.length - 4) {
            long high = Jbd2.get32(block, off);
            long low = Jbd2.get32(block, off + 4);
            long fsBlock = (high << 32) | low;
            int flags = (int) Jbd2.get32(block, off + 8);
            off += 12;
            Integer blockCsum = null;
            if ((flags & Jbd2.TAG_FLAG_LARGE_TAG) != 0
                    || checksumVersion == Jbd2.CHECKSUM_V3_BLOCK) {
                if (off + 4 <= block.length - 4) {
                    blockCsum = (int) Jbd2.get32(block, off);
                    off += 4;
                }
            }
            Tag tag = new Tag(fsBlock, flags, blockCsum);
            tags.add(tag);
            if (tag.last()) {
                break;
            }
        }
        if (checksumVersion != Jbd2.CHECKSUM_V0_NONE) {
            total = (int) Jbd2.get32(block, block.length - 4);
        }
        return new Descriptor(tags, total);
    }

    public static byte[] writeCommit(long sequence, int checksumType, Integer totalChecksum, int blockSize) {
        byte[] block = new byte[blockSize];
        writeHeader(block, Jbd2.TYPE_COMMIT_BLOCK, sequence);
        Jbd2.put8(block, 12, checksumType);
        if (totalChecksum != null) {
            Jbd2.put32(block, 13, totalChecksum);
        }
        return block;
    }

    public static Commit readCommit(byte[] block) {
        int type = Jbd2.get8(block, 12);
        int total = (int) Jbd2.get32(block, 13);
        return new Commit(type, total);
    }

    public static byte[] writeRevoke(long sequence, byte[] uuid, List<Long> fsBlocks, int blockSize) {
        byte[] block = new byte[blockSize];
        writeHeader(block, Jbd2.TYPE_REVOKE_BLOCK, sequence);
        Jbd2.putUuid(block, 12, uuid);
        Jbd2.put32(block, 28, fsBlocks.size());
        int off = 32;
        for (Long fsBlock : fsBlocks) {
            Jbd2.put32(block, off, fsBlock >>> 32);
            Jbd2.put32(block, off + 4, fsBlock & 0xffffffffL);
            off += 8;
        }
        return block;
    }

    public static Revoke readRevoke(byte[] block) {
        int count = (int) Jbd2.get32(block, 28);
        List<Long> ids = new ArrayList<>();
        int off = 32;
        int max = (block.length - off) / 8;
        for (int i = 0; i < count && i < max; i++) {
            long high = Jbd2.get32(block, off);
            long low = Jbd2.get32(block, off + 4);
            ids.add((high << 32) | low);
            off += 8;
        }
        return new Revoke(ids);
    }

    public static byte[] writeEscape(long sequence, byte[] payload) {
        byte[] block = payload.clone();
        // JBD2 escape: payload that starts with the journal magic is hidden
        // behind the escape magic, and the original first word is parked at
        // offset 12 (after a nominal journal header).
        Jbd2.put32(block, 0, Jbd2.ESCAPE_MAGIC);
        Jbd2.put32(block, 12, Jbd2.ESCAPE_MAGIC);
        return block;
    }

    public static boolean isEscape(byte[] block) {
        return block.length >= 16 && Jbd2.get32(block, 0) == Jbd2.ESCAPE_MAGIC;
    }

    public static byte[] unescape(byte[] block) {
        byte[] payload = block.clone();
        Jbd2.put32(payload, 0, Jbd2.HEADER_MAGIC);
        Jbd2.put32(payload, 12, 0);
        return payload;
    }
}
