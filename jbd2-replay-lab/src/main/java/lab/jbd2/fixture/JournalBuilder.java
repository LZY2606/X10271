package lab.jbd2.fixture;

import lab.jbd2.binary.Jbd2Format;
import lab.jbd2.model.BlockKind;
import lab.jbd2.model.ChecksumVersion;
import lab.jbd2.model.JournalImage;
import lab.jbd2.model.Quantity;
import lab.jbd2.model.RawBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static lab.jbd2.binary.Jbd2Format.HEADER_LEN;

/**
 * Builds real JBD2-shaped journal images (superblock + descriptor/data/revoke/commit
 * blocks with valid sealing) purely in-memory. The same builder backs the committed
 * offline case pack and every automated test, so tests never need external files.
 */
public final class JournalBuilder {

    private final int blockSize;
    private final long maxBlocks;
    private final long ringStart;
    private final long firstSeq;
    private final ChecksumVersion checksum;
    private final Map<Long, byte[]> blocks = new LinkedHashMap<>();

    private long cursor;
    private long crashTail;

    public JournalBuilder(int blockSize, long maxBlocks, long ringStart, long firstSeq,
                          ChecksumVersion checksum) {
        this.blockSize = blockSize;
        this.maxBlocks = maxBlocks;
        this.ringStart = ringStart;
        this.firstSeq = firstSeq;
        this.checksum = checksum;
        this.cursor = ringStart;
        blocks.put(0L, Jbd2Format.writeSuperblock(blockSize, maxBlocks, ringStart,
                firstSeq, maxBlocks - 1, checksum.code));
    }

    private long next() {
        long p = cursor;
        cursor = (cursor + 1) % maxBlocks;
        return p;
    }

    /** Appends a complete transaction with one write per (fsBlock, payloadByte) pair. */
    public JournalBuilder committed(long seq, List<long[]> writes) {
        return committedWith(seq, writes, List.of());
    }

    /**
     * Complete transaction whose group is descriptor, data*, revoke?, commit. Data blocks
     * are allocated directly after the descriptor so tag index == physical offset; the
     * optional revoke block precedes the commit.
     */
    public JournalBuilder committedWith(long seq, List<long[]> writes, List<Long> revokeBlocks) {
        int cs = checksum.code;
        long descPhys = next();
        byte[] desc = Jbd2Format.newBlock(blockSize, (byte) 0);
        Jbd2Format.writeHeader(desc, BlockKind.DESCRIPTOR, seq);
        int off = HEADER_LEN;
        int tagBytes = 0;
        for (int i = 0; i < writes.size(); i++) {
            int flags = i == writes.size() - 1 ? Jbd2Format.FLAG_LAST_TAG : 0;
            off = Jbd2Format.writeTag(desc, off, writes.get(i)[0], flags, cs);
            tagBytes += Jbd2Format.tagStride(cs);
        }
        Jbd2Format.sealDescriptor(desc, tagBytes, cs);
        blocks.put(descPhys, desc);
        if (!revokeBlocks.isEmpty()) {
            revoke(seq, revokeBlocks);
        }
        for (long[] w : writes) {
            long dataPhys = next();
            byte[] data = Jbd2Format.newBlock(blockSize, (byte) w[1]);
            Jbd2Format.writeHeader(data, BlockKind.DATA, seq);
            blocks.put(dataPhys, data);
        }

        long commitPhys = next();
        byte[] commit = Jbd2Format.newBlock(blockSize, (byte) 0);
        Jbd2Format.writeHeader(commit, BlockKind.COMMIT, seq);
        Jbd2Format.sealCommit(commit, cs);
        blocks.put(commitPhys, commit);
        return this;
    }

    /** Appends a revoke block for the given filesystem blocks (same transaction seq). */
    public JournalBuilder revoke(long seq, List<Long> fsBlocks) {
        long phys = next();
        byte[] revoke = Jbd2Format.newBlock(blockSize, (byte) 0);
        Jbd2Format.writeHeader(revoke, BlockKind.REVOKE, seq);
        Jbd2Format.putU32(revoke, Jbd2Format.REVOKE_OFF_COUNT, fsBlocks.size());
        for (int i = 0; i < fsBlocks.size(); i++) {
            Jbd2Format.putU32(revoke, Jbd2Format.REVOKE_OFF_TABLE + i * 4, fsBlocks.get(i));
        }
        Jbd2Format.sealRevoke(revoke, checksum.code);
        blocks.put(phys, revoke);
        return this;
    }

    /** Writes a descriptor and its data but never a commit (half transaction). */
    public JournalBuilder halfTransaction(long seq, List<long[]> writes) {
        int cs = checksum.code;
        long descPhys = next();
        byte[] desc = Jbd2Format.newBlock(blockSize, (byte) 0);
        Jbd2Format.writeHeader(desc, BlockKind.DESCRIPTOR, seq);
        int off = HEADER_LEN;
        int tagBytes = 0;
        for (int i = 0; i < writes.size(); i++) {
            int flags = i == writes.size() - 1 ? Jbd2Format.FLAG_LAST_TAG : 0;
            off = Jbd2Format.writeTag(desc, off, writes.get(i)[0], flags, cs);
            tagBytes += Jbd2Format.tagStride(cs);
            long dataPhys = next();
            byte[] data = Jbd2Format.newBlock(blockSize, (byte) writes.get(i)[1]);
            Jbd2Format.writeHeader(data, BlockKind.DATA, seq);
            blocks.put(dataPhys, data);
        }
        Jbd2Format.sealDescriptor(desc, tagBytes, cs);
        blocks.put(descPhys, desc);
        return this;
    }

    /** Corrupts the trailing checksum of the commit block at a physical index. */
    public JournalBuilder corruptCommit(long commitPhysical) {
        byte[] b = blocks.get(commitPhysical).clone();
        Jbd2Format.putU32(b, Jbd2Format.COMMIT_OFF_CHECKSUM, 0xdeadbeefL);
        blocks.put(commitPhysical, b);
        return this;
    }

    public JournalBuilder crashTail(long physical) {
        this.crashTail = physical;
        return this;
    }

    public long cursorPhysical() {
        return cursor;
    }

    public long physicalOfLastCommit() {
        long found = -1;
        for (Map.Entry<Long, byte[]> e : blocks.entrySet()) {
            if (Jbd2Format.magicOk(e.getValue())
                    && Jbd2Format.kindOf(e.getValue()) == BlockKind.COMMIT) {
                found = e.getKey();
            }
        }
        return found;
    }

    public JournalImage build(String label) {
        long tail = crashTail == 0 && crashTail != ringStart ? cursor : crashTail;
        List<RawBlock> raw = new ArrayList<>();
        for (Map.Entry<Long, byte[]> e : blocks.entrySet()) {
            raw.add(new RawBlock(e.getKey(), e.getValue(), "fixture", label));
        }
        return JournalImage.valid(label, blockSize, ringStart, firstSeq, maxBlocks,
                tail == 0 ? cursor : tail, checksum, raw);
    }

    /** Build variant retaining an invalid/raw block size (unconvertible at the origin). */
    public JournalImage buildWithRawBlockSize(String label, String rawSize, String diagnostic) {
        JournalImage base = build(label);
        return new JournalImage(label,
                Quantity.raw(rawSize, "B", "bytes per journal block (superblock s_blocksize)",
                        diagnostic),
                base.ringStart(), base.expectedFirstSequence(), base.maxJournalBlocks(),
                base.crashTailBlock(), base.checksumVersion(), base.blocks(),
                List.of(Quantity.raw(rawSize, "B", "s_blocksize", diagnostic)));
    }
}
