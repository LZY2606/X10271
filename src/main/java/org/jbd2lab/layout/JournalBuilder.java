package org.jbd2lab.layout;

import org.jbd2lab.domain.Capture;
import org.jbd2lab.domain.RawBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic builder of JBD2-LAB ring captures for fixtures and tests.
 * Slots are placed by absolute physical ring index; callers drive wrap-around by continuing to
 * append past the ring length (the builder does not reorder — the scanner walks the ring).
 */
public final class JournalBuilder {
    private final int blockSize;
    private final int checksumVersion;
    private final long journalStart;
    private final long ringSlots;
    private final List<Placed> placed = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private String blockSizeRaw;
    private String name = "capture";
    private long cursor;

    private record Placed(long ringIndex, byte[] bytes) {}

    public JournalBuilder(int blockSize, int checksumVersion, long journalStart, long ringSlots) {
        this.blockSize = blockSize;
        this.checksumVersion = checksumVersion;
        this.journalStart = journalStart;
        this.ringSlots = ringSlots;
        this.cursor = journalStart;
        this.blockSizeRaw = Integer.toString(blockSize);
    }

    public JournalBuilder name(String n) { this.name = n; return this; }
    public JournalBuilder declaredBlockSizeRaw(String raw) { this.blockSizeRaw = raw; return this; }
    public long cursor() { return cursor; }
    public JournalBuilder seek(long ringIndex) { this.cursor = ringIndex; return this; }
    public int checksumVersion() { return checksumVersion; }

    /** Append one full transaction: descriptor + data blocks + commit. Returns start index. */
    public long transaction(long seq, long[] fsBlocks, byte[][] payloads) {
        return transaction(seq, fsBlocks, payloads, null, 0);
    }

    public long transaction(long seq, long[] fsBlocks, byte[][] payloads, int[] extraFlags,
                            int flagsMask) {
        int[] flags = new int[fsBlocks.length];
        if (extraFlags != null) {
            System.arraycopy(extraFlags, 0, flags, 0, fsBlocks.length);
        }
        long start = cursor;
        byte[] desc = Codec.descriptor(blockSize, seq, fsBlocks, flags, checksumVersion);
        place(desc);
        long descCrc = checksumVersion == Jbd2.CSUM_VERSION_1
                ? org.jbd2lab.util.Be.u32(desc, blockSize - 4) : 0L;
        for (int i = 0; i < fsBlocks.length; i++) {
            boolean deleted = (flags[i] & Jbd2.TAG_FLAG_DELETED) != 0;
            byte[] payload = deleted ? new byte[blockSize]
                    : (payloads != null && payloads[i] != null ? payloads[i]
                        : payloadFor(fsBlocks[i], seq));
            place(Codec.dataBlock(blockSize, payload, checksumVersion));
        }
        place(Codec.commitBlock(blockSize, seq, descCrc, checksumVersion));
        return start;
    }

    /** Transaction with an optional revoke block placed right after the descriptor. */
    public long transactionWithRevoke(long seq, long[] fsBlocks, byte[][] payloads, long[] revoked) {
        int[] flags = new int[fsBlocks.length];
        long start = cursor;
        byte[] desc = Codec.descriptor(blockSize, seq, fsBlocks, flags, checksumVersion);
        place(desc);
        long descCrc = checksumVersion == Jbd2.CSUM_VERSION_1
                ? org.jbd2lab.util.Be.u32(desc, blockSize - 4) : 0L;
        if (revoked != null && revoked.length > 0) {
            place(Codec.revokeBlock(blockSize, seq, revoked, checksumVersion));
        }
        for (int i = 0; i < fsBlocks.length; i++) {
            place(Codec.dataBlock(blockSize,
                    payloads != null && payloads[i] != null ? payloads[i] : payloadFor(fsBlocks[i], seq),
                    checksumVersion));
        }
        place(Codec.commitBlock(blockSize, seq, descCrc, checksumVersion));
        return start;
    }

    /** Descriptor + data but no commit (half transaction at a crash boundary). */
    public long halfTransaction(long seq, long[] fsBlocks, byte[][] payloads) {
        int[] flags = new int[fsBlocks.length];
        place(Codec.descriptor(blockSize, seq, fsBlocks, flags, checksumVersion));
        for (int i = 0; i < fsBlocks.length; i++) {
            place(Codec.dataBlock(blockSize,
                    payloads != null && payloads[i] != null ? payloads[i] : payloadFor(fsBlocks[i], seq),
                    checksumVersion));
        }
        return cursor - fsBlocks.length - 1;
    }

    public JournalBuilder rawBlock(byte[] bytes) {
        place(bytes);
        return this;
    }

    public JournalBuilder corruptNextTail() {
        pendingCorruptTail = true;
        return this;
    }

    private boolean pendingCorruptTail;

    private void place(byte[] bytes) {
        byte[] out = bytes;
        if (pendingCorruptTail) {
            out = Codec.withTailCrcCorrupted(bytes);
            pendingCorruptTail = false;
        }
        placed.add(new Placed(cursor, out));
        cursor++;
    }

    /** Replace the block previously placed at ringIndex (source mutation support). */
    public JournalBuilder replace(long ringIndex, byte[] bytes) {
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).ringIndex == ringIndex) {
                placed.set(i, new Placed(ringIndex, bytes));
                return this;
            }
        }
        placed.add(new Placed(ringIndex, bytes));
        return this;
    }

    /** Deterministic payload so regenerated fixtures are byte-identical. */
    public static byte[] payloadFor(long fsBlock, long seq) {
        byte[] b = new byte[64];
        String s = "fsblk=" + fsBlock + " seq=" + seq;
        byte[] sb = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(sb, 0, b, 0, Math.min(sb.length, b.length));
        return b;
    }

    public byte[] superblockBytes(long first, long startSeq, long maxLen) {
        return Codec.superblock(blockSize, checksumVersion, first, startSeq, maxLen);
    }

    public Capture build(long superblockRingIndex) {
        List<RawBlock> blocks = new ArrayList<>();
        blocks.add(new RawBlock(superblockRingIndex,
                Codec.superblock(blockSize, checksumVersion, journalStart, 0L, ringSlots),
                "journal-superblock"));
        for (Placed p : placed) {
            blocks.add(new RawBlock(p.ringIndex, p.bytes, "journal-ring"));
        }
        return new Capture(name, blockSizeRaw, blockSize, journalStart, ringSlots,
                Integer.toString(checksumVersion), blocks, "journal-superblock@"
                        + superblockRingIndex, List.copyOf(diagnostics));
    }
}
