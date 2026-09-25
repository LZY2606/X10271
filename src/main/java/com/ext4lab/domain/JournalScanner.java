package com.ext4lab.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the journal ring in physical order from the superblock start, grouping
 * descriptor/data/commit into transactions. Semantics enforced here:
 * - only transactions with a complete commit block are replayable;
 * - sequence numbers wrap; physical walk order plus modular comparison, never integer sort;
 * - a checksum failure opens an uncertainty boundary: everything at/after it stays undecided.
 */
public final class JournalScanner {
    private JournalScanner() {}

    public static Model.ScanResult scan(Model.Superblock sb, List<Model.JournalBlock> ring,
                                        int crashIndex, boolean ignoreChecksums) {
        List<Model.Txn> txns = new ArrayList<>();
        List<Model.Revoke> revokes = new ArrayList<>();
        List<Model.Anomaly> anomalies = new ArrayList<>();
        Integer boundary = null;

        if (!sb.blockSizeValid()) {
            anomalies.add(new Model.Anomaly(Model.AnomalyKind.INVALID_BLOCK_SIZE, -1, sb.startSequence(),
                    "blockSize=" + sb.blockSizeBytes()
                            + " B is not a power of two in [1024,65536]; raw value preserved, decoding blocked",
                    List.of("SB.blockSizeBytes")));
            return new Model.ScanResult(sb, ring, crashIndex, txns, revokes, anomalies, 0);
        }

        int limit = Math.min(crashIndex, ring.size());
        long lastSeq = -1;
        boolean stop = false;
        int pos = 0;
        while (pos < limit && !stop) {
            Model.JournalBlock jb = ring.get(pos);
            byte[] raw = jb.raw();
            if (!JournalFormat.hasMagic(raw)) {
                anomalies.add(new Model.Anomaly(Model.AnomalyKind.HALF_WRITE, pos, -1,
                        "no journal magic at ring block " + pos + "/" + ring.size()
                                + "; treated as crash truncation", List.of(jb.id())));
                break;
            }
            if (!ignoreChecksums && !JournalFormat.checksumOk(raw, sb.checksumVersion())) {
                anomalies.add(new Model.Anomaly(Model.AnomalyKind.CHECKSUM_BREAK, pos,
                        JournalFormat.seqOf(raw),
                        "crc32c mismatch at ring block " + pos + "/" + ring.size()
                                + "; everything from here on stays undecided",
                        List.of(jb.id())));
                boundary = pos;
                break;
            }
            int type = JournalFormat.typeOf(raw);
            long seq = JournalFormat.seqOf(raw);
            if (lastSeq >= 0 && seq != lastSeq && seq < lastSeq
                    && SeqNo.distance(lastSeq, seq) < SeqNo.HALF) {
                anomalies.add(new Model.Anomaly(Model.AnomalyKind.RING_WRAP, pos, seq,
                        "sequence wrapped " + SeqNo.hex(lastSeq) + " -> " + SeqNo.hex(seq)
                                + " at ring block " + pos + "; modular ordering applied, not integer sort",
                        List.of(jb.id())));
            }
            if (type != JournalFormat.TYPE_DATA) {
                lastSeq = seq;
            }
            switch (type) {
                case JournalFormat.TYPE_DESCRIPTOR -> {
                    List<Model.Tag> tags = JournalFormat.tagsOf(raw);
                    List<Model.DataEntry> data = new ArrayList<>();
                    int next = pos + 1;
                    boolean truncated = false;
                    for (Model.Tag tag : tags) {
                        if (next >= limit) {
                            truncated = true;
                            break;
                        }
                        data.add(new Model.DataEntry(next, tag.fsBlock(), ring.get(next).raw()));
                        next++;
                    }
                    boolean committed = false;
                    int commitIdx = -1;
                    if (!truncated && next < limit) {
                        byte[] maybe = ring.get(next).raw();
                        if (JournalFormat.hasMagic(maybe)
                                && JournalFormat.typeOf(maybe) == JournalFormat.TYPE_COMMIT
                                && JournalFormat.seqOf(maybe) == seq
                                && (ignoreChecksums
                                    || JournalFormat.checksumOk(maybe, sb.checksumVersion()))) {
                            committed = true;
                            commitIdx = next;
                        }
                    }
                    if (!committed) {
                        List<String> affected = new ArrayList<>();
                        affected.add("TXN#" + SeqNo.hex(seq));
                        for (Model.DataEntry d : data) {
                            affected.add("JB#" + d.ringIndex());
                        }
                        anomalies.add(new Model.Anomaly(Model.AnomalyKind.HALF_TRANSACTION, pos, seq,
                                "descriptor at ring block " + pos + " has no matching commit before"
                                        + " crash index " + crashIndex + "; transaction is not replayable",
                                affected));
                        stop = true; // anything beyond a half transaction is undecidable
                    } else {
                        txns.add(new Model.Txn(seq, data, true, commitIdx));
                        pos = commitIdx + 1;
                    }
                }
                case JournalFormat.TYPE_REVOKE -> {
                    revokes.add(new Model.Revoke(seq, pos, JournalFormat.revokeEntries(raw)));
                    pos++;
                }
                case JournalFormat.TYPE_COMMIT -> {
                    anomalies.add(new Model.Anomaly(Model.AnomalyKind.STRAY_COMMIT, pos, seq,
                            "commit block without preceding descriptor", List.of(jb.id())));
                    pos++;
                }
                default -> pos++;
            }
        }
        return new Model.ScanResult(sb, ring, crashIndex, txns, revokes, anomalies, boundary);
    }
}
