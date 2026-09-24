package ext4lab.journal;

import ext4lab.journal.Model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans a JBD2 journal ring. Semantics preserved here:
 * - ring is walked from the superblock start block, never sorted by plain integer sequence;
 * - 32-bit sequences wrap; ordering is relative to firstSeq;
 * - only transactions with a complete commit are replayable (enforced in ReplayEngine);
 * - a commit-checksum failure sets an uncertainty boundary: everything beyond stays unknown.
 */
public final class JournalScanner {

    public ScanResult scan(Superblock sb, byte[] journal, int crashAfter) {
        List<JBlock> blocks = new ArrayList<>();
        List<Txn> txns = new ArrayList<>();
        List<Judgment> judgments = new ArrayList<>();
        List<Anomaly> anomalies = new ArrayList<>();

        if (!sb.usable()) {
            anomalies.add(new Anomaly("invalid-block-size", "superblock",
                    "raw blockSize='" + sb.rawBlockSize() + "' cannot be normalized; value preserved in place, scan blocked",
                    "src/superblock"));
            judgments.add(new Judgment("invalid-block-size-preserved", "superblock",
                    "blocked", "raw value kept with diagnostic instead of being dropped for plotting"));
            return new ScanResult(sb, crashAfter, blocks, txns, judgments, anomalies, null,
                    "invalid-block-size: " + sb.rawBlockSize());
        }

        int bs = sb.blockSize();
        int limit = Math.min(crashAfter, sb.journalBlocks());
        String stopReason = "crash-point reached at ring offset " + limit;
        Integer checksumBreak = null;
        boolean sawPhysicalWrap = false;
        long prevSeq = -1;
        boolean sawSeqWrap = false;

        List<JBlock> openDesc = new ArrayList<>();
        List<JBlock> openData = new ArrayList<>();
        List<Long> openRevoke = new ArrayList<>();
        long openSeq = -1;
        int openStart = -1;
        int dataExpected = 0;

        ringWalk:
        for (int i = 0; i < limit; i++) {
            int idx = (sb.journalStartBlock() + i) % sb.journalBlocks();
            if (idx < sb.journalStartBlock()) sawPhysicalWrap = true;
            byte[] raw = new byte[bs];
            System.arraycopy(journal, idx * bs, raw, 0, bs);

            if (dataExpected > 0) {
                JBlock d = new JBlock(i, idx, false, -1, openSeq, List.of(), List.of(),
                        null, null, true, "data");
                blocks.add(d);
                openData.add(d);
                dataExpected--;
                continue;
            }

            var bb = JournalFormat.buf(raw);
            long magic = bb.getInt(0) & 0xFFFFFFFFL;
            if (magic != JournalFormat.MAGIC) {
                if (!openDesc.isEmpty() || !openData.isEmpty() || !openRevoke.isEmpty()) {
                    txns.add(new Txn(openSeq, openStart, List.copyOf(openDesc), List.copyOf(openData),
                            List.copyOf(openRevoke), null, Txn.Status.HALF,
                            "no commit before end-of-ring at offset " + i));
                    anomalies.add(new Anomaly("half-transaction", "txn:" + openSeq,
                            "descriptor/data present but commit missing before ring offset " + i,
                            "jb:" + openStart));
                }
                stopReason = "end-of-ring at offset " + i + " (bad magic)";
                break;
            }
            int type = bb.getInt(4);
            long seq = bb.getInt(8) & 0xFFFFFFFFL;

            if (JournalFormat.isStale(seq, sb.firstSeq())) {
                blocks.add(new JBlock(i, idx, true, type, seq, List.of(), List.of(),
                        null, null, true, "stale: previous ring generation"));
                anomalies.add(new Anomaly("stale-generation", "jb:" + idx,
                        "seq " + seq + " predates firstSeq " + sb.firstSeq() + "; ring walk stops", "jb:" + idx));
                stopReason = "stale generation at offset " + i;
                break;
            }
            if (prevSeq >= 0 && seq < prevSeq) sawSeqWrap = true;
            prevSeq = seq;

            switch (type) {
                case JournalFormat.TYPE_DESCRIPTOR -> {
                    if (!openDesc.isEmpty() || !openData.isEmpty() || !openRevoke.isEmpty()) {
                        txns.add(new Txn(openSeq, openStart, List.copyOf(openDesc), List.copyOf(openData),
                                List.copyOf(openRevoke), null, Txn.Status.HALF,
                                "superseded by new descriptor at offset " + i));
                        anomalies.add(new Anomaly("half-transaction", "txn:" + openSeq,
                                "new descriptor arrived before commit", "jb:" + idx));
                    }
                    List<Tag> tags = new ArrayList<>();
                    for (int off = JournalFormat.HEADER_LEN; off + JournalFormat.TAG_LEN <= bs; off += JournalFormat.TAG_LEN) {
                        long fsBlock = bb.getInt(off) & 0xFFFFFFFFL;
                        int flags = bb.getShort(off + 4) & 0xFFFF;
                        int csum16 = bb.getShort(off + 6) & 0xFFFF;
                        tags.add(new Tag(fsBlock, flags, csum16));
                        if ((flags & JournalFormat.TAG_LAST) != 0) break;
                    }
                    JBlock d = new JBlock(i, idx, true, type, seq, tags, List.of(),
                            null, null, true, "descriptor tags=" + tags.size());
                    blocks.add(d);
                    openDesc = new ArrayList<>(List.of(d));
                    openData = new ArrayList<>();
                    openRevoke = new ArrayList<>();
                    openSeq = seq;
                    openStart = i;
                    dataExpected = tags.size();
                }
                case JournalFormat.TYPE_REVOKE -> {
                    if (!openDesc.isEmpty() || !openData.isEmpty() || !openRevoke.isEmpty()) {
                        txns.add(new Txn(openSeq, openStart, List.copyOf(openDesc), List.copyOf(openData),
                                List.copyOf(openRevoke), null, Txn.Status.HALF,
                                "revoke of seq " + seq + " arrived before commit"));
                        anomalies.add(new Anomaly("half-transaction", "txn:" + openSeq,
                                "revoke arrived before commit", "jb:" + idx));
                    }
                    int count = bb.getInt(JournalFormat.HEADER_LEN);
                    List<Long> revoked = new ArrayList<>();
                    for (int k = 0; k < count; k++) {
                        revoked.add(bb.getInt(JournalFormat.HEADER_LEN + 4 + k * 4) & 0xFFFFFFFFL);
                    }
                    JBlock r = new JBlock(i, idx, true, type, seq, List.of(), revoked,
                            null, null, true, "revoke count=" + count);
                    blocks.add(r);
                    openDesc = new ArrayList<>();
                    openData = new ArrayList<>();
                    openRevoke = new ArrayList<>(revoked);
                    openSeq = seq;
                    openStart = i;
                    dataExpected = 0;
                }
                case JournalFormat.TYPE_COMMIT -> {
                    long stored = bb.getInt(JournalFormat.HEADER_LEN) & 0xFFFFFFFFL;
                    long computed = computeCommitChecksum(sb, journal, bs, openDesc, openData);
                    boolean ok = stored == computed;
                    JBlock c = new JBlock(i, idx, true, type, seq, List.of(), List.of(),
                            stored, computed, ok, ok ? "commit" : "commit CHECKSUM MISMATCH");
                    blocks.add(c);
                    if (!ok) {
                        txns.add(new Txn(openSeq, openStart, List.copyOf(openDesc), List.copyOf(openData),
                                List.copyOf(openRevoke), c, Txn.Status.UNCERTAIN,
                                "commit checksum mismatch: stored=" + stored + " computed=" + computed));
                        checksumBreak = i;
                        anomalies.add(new Anomaly("checksum-break", "txn:" + openSeq,
                                "commit checksum mismatch at ring offset " + i
                                        + "; boundary kept uncertain, blocks beyond are unknown", "jb:" + idx));
                        judgments.add(new Judgment("checksum-boundary-uncertainty", "txn:" + openSeq,
                                "uncertain", "checksum failure at offset " + i
                                        + "; replay candidates fork here, later blocks not trusted"));
                        stopReason = "checksum boundary at offset " + i;
                        openDesc = new ArrayList<>(); openData = new ArrayList<>(); openRevoke = new ArrayList<>();
                        break ringWalk;
                    }
                    txns.add(new Txn(openSeq, openStart, List.copyOf(openDesc), List.copyOf(openData),
                            List.copyOf(openRevoke), c, Txn.Status.COMPLETE, "commit verified"));
                    openDesc = new ArrayList<>(); openData = new ArrayList<>(); openRevoke = new ArrayList<>();
                }
                default -> {
                    blocks.add(new JBlock(i, idx, true, type, seq, List.of(), List.of(),
                            null, null, true, "journal superblock inside ring"));
                }
            }
        }
        if (!openDesc.isEmpty() || !openData.isEmpty() || !openRevoke.isEmpty()) {
            txns.add(new Txn(openSeq, openStart, List.copyOf(openDesc), List.copyOf(openData),
                    List.copyOf(openRevoke), null, Txn.Status.HALF,
                    "crash point reached before commit"));
            anomalies.add(new Anomaly("half-transaction", "txn:" + openSeq,
                    "crash point reached before commit", "jb:" + openStart));
        }
        if (sawPhysicalWrap || sawSeqWrap) {
            judgments.add(new Judgment("ring-wrap-no-int-sort", "journal-ring",
                    "ring-order", "ring walked from start block " + sb.journalStartBlock()
                            + (sawSeqWrap ? "; 32-bit sequence wrapped" : "")
                            + "; ordering is relative to firstSeq " + sb.firstSeq()
                            + ", never plain integer sort"));
        }
        return new ScanResult(sb, crashAfter, blocks, txns, judgments, anomalies, checksumBreak, stopReason);
    }

    private long computeCommitChecksum(Superblock sb, byte[] journal, int bs,
                                       List<JBlock> desc, List<JBlock> data) {
        List<byte[]> parts = new ArrayList<>();
        for (JBlock b : desc) parts.add(slice(journal, b.absIndex(), bs));
        for (JBlock b : data) parts.add(slice(journal, b.absIndex(), bs));
        byte[][] arr = parts.toArray(new byte[0][]);
        if (sb.checksumVersion() == 3) {
            return JournalFormat.commitChecksumV3(new byte[16], arr);
        }
        return JournalFormat.commitChecksumV2(arr);
    }

    static byte[] slice(byte[] journal, int absIndex, int bs) {
        byte[] out = new byte[bs];
        System.arraycopy(journal, absIndex * bs, out, 0, bs);
        return out;
    }
}
