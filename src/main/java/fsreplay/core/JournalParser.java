package fsreplay.core;

import fsreplay.core.Model.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a JBD2 journal ring and groups blocks into transactions.
 * Semantics enforced here:
 *  - ring sequence numbers wrap at 2^32; scan order (from s_start) is canonical, never plain int sort
 *  - checksum failure marks the boundary: scanning stops, everything past it stays uncertain
 *  - invalid block size: kept raw with a diagnostic, no derivation is attempted
 */
public final class JournalParser {

    public record Input(byte[] image, Quantity blockSize, Quantity startBlock, Quantity ringBlocks,
                        Checksum checksum, Integer crashPoint /* max blocks to scan, null = full ring */) {}

    public static ScanResult parse(Input in) {
        List<String> diags = Model.newDiags();
        List<ParsedBlock> blocks = new ArrayList<>();
        List<TxnGroup> txns = new ArrayList<>();

        Long bs = in.blockSize() == null ? null : in.blockSize().normalized();
        if (bs == null || bs < 1024 || bs > 65536 || (bs & (bs - 1)) != 0) {
            diags.add("invalid block size: raw=" + (in.blockSize() == null ? "null" : in.blockSize().raw())
                    + " unit=bytes — kept in place, no derivation attempted");
            SuperblockInfo sbi = new SuperblockInfo(in.blockSize(), Quantity.raw("0", "journal-blocks"),
                    Quantity.of(1, "journal-block"), in.startBlock(), Quantity.raw("0", "u32-seq"),
                    in.checksum() == null ? "unknown" : in.checksum().label, diags);
            return new ScanResult(sbi, blocks, txns, null, diags);
        }
        int blockSize = bs.intValue();
        if (in.image() == null || in.image().length % blockSize != 0) {
            diags.add("journal image length " + (in.image() == null ? 0 : in.image().length)
                    + " bytes is not a multiple of block size " + blockSize + " bytes");
        }
        int ring = in.image() == null ? 0 : in.image().length / blockSize;
        int start = in.startBlock() != null && in.startBlock().normalized() != null
                ? in.startBlock().normalized().intValue() : 1;
        if (start <= 0 || start >= ring) {
            diags.add("ring start " + start + " outside ring of " + ring + " blocks; clamped to 1");
            start = ring > 1 ? 1 : 0;
        }
        int maxScan = in.crashPoint() != null ? Math.min(in.crashPoint(), ring) : ring;

        Integer checksumBreak = null;
        int idx = start;
        TxnBuilder open = null;
        for (int scanned = 0; scanned < maxScan; scanned++) {
            if (idx * blockSize + blockSize > in.image().length) break;
            byte[] blk = new byte[blockSize];
            System.arraycopy(in.image(), idx * blockSize, blk, 0, blockSize);

            // Data blocks carry no journal header: consume them while a txn expects them.
            if (open != null && !open.pendingTags.isEmpty()) {
                long fsb = open.pendingTags.remove(0);
                ParsedBlock pb = new ParsedBlock(idx, BlockKind.DATA, open.sequence,
                        List.of(fsb), List.of(), null, true, null);
                open.data.add(pb);
                open.rawData.add(blk);
                blocks.add(pb);
                idx = next(idx, ring);
                if (idx == start) break;
                continue;
            }

            long magic = JournalFormat.get32(blk, 0);
            if (magic != JournalFormat.MAGIC) {
                if (scanned == 0) diags.add("no journal header at ring start " + start);
                break; // end of written log
            }
            int type = (int) JournalFormat.get32(blk, 4);
            long seq = JournalFormat.get32(blk, 8);
            switch (type) {
                case JournalFormat.TYPE_DESCRIPTOR -> {
                    if (open != null) {
                        open.diags.add("half transaction: descriptor for seq " + Seq.hex(seq)
                                + " appeared before commit of seq " + Seq.hex(open.sequence));
                        txns.add(open.build());
                    }
                    open = new TxnBuilder(seq);
                    List<Long> fs = new ArrayList<>();
                    List<Long> crcs = new ArrayList<>();
                    for (int off = JournalFormat.HEADER_SIZE; off + JournalFormat.TAG_SIZE <= blockSize; off += JournalFormat.TAG_SIZE) {
                        long fsb = JournalFormat.get32(blk, off);
                        long crc = JournalFormat.get32(blk, off + 4);
                        int flags = JournalFormat.get16(blk, off + 8);
                        if (fsb == 0 && crc == 0 && flags == 0) break;
                        fs.add(fsb);
                        crcs.add(crc);
                        if ((flags & JournalFormat.FLAG_LAST) != 0) break;
                    }
                    ParsedBlock pb = new ParsedBlock(idx, BlockKind.DESCRIPTOR, seq, fs, crcs, null, true, null);
                    blocks.add(pb);
                    open.descriptors.add(pb);
                    open.rawDesc.add(blk);
                    open.pendingTags.addAll(fs);
                }
                case JournalFormat.TYPE_COMMIT -> {
                    long stored = JournalFormat.get32(blk, JournalFormat.HEADER_SIZE);
                    boolean ok = false;
                    if (open != null && open.sequence == seq) {
                        long expect = commitChecksum(in.checksum(), seq, open, blk);
                        ok = stored == expect;
                        if (!ok) {
                            diags.add("commit checksum mismatch at journal block " + idx + " seq " + Seq.hex(seq)
                                    + ": stored=" + String.format("0x%08X", stored)
                                    + " computed=" + String.format("0x%08X", expect)
                                    + " — boundary uncertain, scan stops here");
                        }
                    } else {
                        diags.add("commit without open transaction at journal block " + idx);
                    }
                    ParsedBlock pb = new ParsedBlock(idx, BlockKind.COMMIT, seq, List.of(), List.of(), stored, ok, null);
                    blocks.add(pb);
                    if (open != null) {
                        open.commit = pb;
                        txns.add(open.build());
                        open = null;
                    }
                    if (!ok) {
                        checksumBreak = idx;
                    }
                }
                case JournalFormat.TYPE_REVOKE -> {
                    if (open != null) {
                        open.diags.add("half transaction: revoke seq " + Seq.hex(seq)
                                + " appeared before commit of seq " + Seq.hex(open.sequence));
                        txns.add(open.build());
                        open = null;
                    }
                    long count = JournalFormat.get32(blk, JournalFormat.HEADER_SIZE);
                    List<Long> revoked = new ArrayList<>();
                    for (int i = 0; i < count / 4 && JournalFormat.HEADER_SIZE + 4 + i * 4 + 4 <= blockSize; i++) {
                        revoked.add(JournalFormat.get32(blk, JournalFormat.HEADER_SIZE + 4 + i * 4));
                    }
                    ParsedBlock pb = new ParsedBlock(idx, BlockKind.REVOKE, seq, revoked, List.of(), null, true, null);
                    blocks.add(pb);
                    TxnBuilder rb = new TxnBuilder(seq);
                    rb.revokes.add(pb);
                    txns.add(rb.build());
                }
                case JournalFormat.TYPE_SUPER_V1, JournalFormat.TYPE_SUPER_V2 ->
                    blocks.add(new ParsedBlock(idx, BlockKind.SUPERBLOCK, seq, List.of(), List.of(), null, true, null));
                default ->
                    blocks.add(new ParsedBlock(idx, BlockKind.UNKNOWN, seq, List.of(), List.of(), null, true,
                            "unrecognised block type " + type));
            }
            if (checksumBreak != null) break;
            idx = next(idx, ring);
            if (idx == start) break;
        }
        if (open != null) {
            open.diags.add("half transaction at end of scan: seq " + Seq.hex(open.sequence) + " has no commit");
            txns.add(open.build());
        }

        SuperblockInfo sbi = new SuperblockInfo(in.blockSize(), Quantity.of(ring, "journal-blocks"),
                Quantity.of(1, "journal-block"), in.startBlock(), Quantity.raw("0", "u32-seq"),
                in.checksum() == null ? "unknown" : in.checksum().label, diags);
        return new ScanResult(sbi, blocks, txns, checksumBreak, diags);
    }

    private static int next(int idx, int ring) {
        int n = (idx + 1) % ring;
        return n == 0 ? 1 : n; // journal superblock stays out of the ring scan
    }

    private static long commitChecksum(Checksum cs, long seq, TxnBuilder open, byte[] commitBlk) {
        java.util.zip.Checksum algo = cs == Checksum.V3 ? new java.util.zip.CRC32C() : new java.util.zip.CRC32();
        algo.update((int) seq);
        for (byte[] d : open.rawDesc) algo.update(d, 0, d.length);
        for (byte[] d : open.rawData) algo.update(d, 0, d.length);
        algo.update(commitBlk, 0, JournalFormat.HEADER_SIZE);
        return algo.getValue() & 0xFFFFFFFFL;
    }

    private static final class TxnBuilder {
        final long sequence;
        final List<ParsedBlock> descriptors = new ArrayList<>();
        final List<ParsedBlock> data = new ArrayList<>();
        final List<ParsedBlock> revokes = new ArrayList<>();
        final List<byte[]> rawDesc = new ArrayList<>();
        final List<byte[]> rawData = new ArrayList<>();
        final List<Long> pendingTags = new ArrayList<>();
        final List<String> diags = new ArrayList<>();
        ParsedBlock commit;

        TxnBuilder(long sequence) { this.sequence = sequence; }

        TxnGroup build() {
            return new TxnGroup(sequence, List.copyOf(descriptors), List.copyOf(data), commit,
                    List.copyOf(revokes), List.copyOf(diags));
        }
    }
}
