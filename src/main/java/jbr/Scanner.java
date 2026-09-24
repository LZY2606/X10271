package jbr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the journal ring starting at ringStart, wrapping around the ring end.
 * Ordering comes from ring position, never from plain integer sorting of
 * sequence numbers (sequences wrap at 2^32). A checksum failure or invalid
 * block is a hard boundary: everything past it stays uncertain.
 */
public final class Scanner {

    public static final class DataRef {
        public final long fsBlock;
        public final int journalIndex; // journal-block-index of the data block
        public DataRef(long fsBlock, int journalIndex) {
            this.fsBlock = fsBlock;
            this.journalIndex = journalIndex;
        }
    }

    public static final class Txn {
        public final long sequence;          // u32, may have wrapped
        public final int ringOrder;          // position in ring walk (authoritative order)
        public final int descriptorIndex;    // journal-block-index
        public final List<DataRef> data = new ArrayList<>();
        public final List<Long> revokedBlocks = new ArrayList<>();
        public int revokeIndex = -1;
        public int commitIndex = -1;         // -1 => no commit => half transaction
        public Txn(long sequence, int ringOrder, int descriptorIndex) {
            this.sequence = sequence;
            this.ringOrder = ringOrder;
            this.descriptorIndex = descriptorIndex;
        }
        public boolean isComplete() { return commitIndex >= 0; }
        public String id() { return "txn:seq:" + sequence; }
    }

    public static final class Anomaly {
        public final String id;
        public final String kind;     // checksum-break | invalid-block | half-transaction | invalid-block-size
        public final int journalIndex;
        public final String detail;
        public Anomaly(String id, String kind, int journalIndex, String detail) {
            this.id = id;
            this.kind = kind;
            this.journalIndex = journalIndex;
            this.detail = detail;
        }
    }

    public static final class ScanResult {
        public final List<Txn> txns = new ArrayList<>();               // ring order
        public final List<Anomaly> anomalies = new ArrayList<>();
        public int boundaryIndex = -1;   // first journal block not safely readable; -1 = clean
        public String boundaryReason = "";
        /** journal-block-indices in the order they were walked (wrap-aware). */
        public final List<Integer> walkOrder = new ArrayList<>();
        public boolean sequenceWrapped;  // true if any later txn has a smaller integer sequence

        public Txn lastCompleteTxn() {
            Txn last = null;
            for (Txn t : txns) if (t.isComplete()) last = t;
            return last;
        }
    }

    public ScanResult scan(Journal j) {
        ScanResult r = new ScanResult();
        int n = j.numBlocks();
        int pos = j.ringStart;
        Txn current = null;
        long prevSeq = -1;
        for (int step = 0; step < n; step++) {
            r.walkOrder.add(pos);
            byte[] block = j.blocks.get(pos);
            Journal.Header h = Journal.parseHeader(block);
            if (h == null) {
                if (allZero(block)) break; // clean tail of the ring
                r.boundaryIndex = pos;
                r.boundaryReason = "invalid-block";
                r.anomalies.add(new Anomaly("anom:invalid-block@" + pos, "invalid-block", pos,
                        "block " + pos + " has no JBD2 magic and is not zeroed; ring tail uncertain"));
                break;
            }
            if (h.blockType == Journal.BT_SUPER_V1 || h.blockType == Journal.BT_SUPER_V2) {
                pos = (pos + 1) % n;
                continue;
            }
            if (!j.verifyChecksum(h.blockType, h.sequence, block)) {
                r.boundaryIndex = pos;
                r.boundaryReason = "checksum-break";
                r.anomalies.add(new Anomaly("anom:checksum-break@" + pos, "checksum-break", pos,
                        "checksum verification failed at block " + pos
                                + " (type " + h.blockType + ", seq " + h.sequence
                                + "); boundary kept, tail stays uncertain"));
                break;
            }
            switch (h.blockType) {
                case Journal.BT_DESCRIPTOR -> {
                    if (current != null && !current.isComplete()) {
                        r.anomalies.add(new Anomaly("anom:half-transaction@" + current.descriptorIndex,
                                "half-transaction", current.descriptorIndex,
                                "transaction seq " + current.sequence + " has no commit before next descriptor"));
                    }
                    if (prevSeq >= 0 && h.sequence < prevSeq) r.sequenceWrapped = true;
                    prevSeq = h.sequence;
                    current = new Txn(h.sequence, r.txns.size(), pos);
                    r.txns.add(current);
                    List<Journal.Tag> tags = Journal.parseTags(block);
                    int dataPos = (pos + 1) % n;
                    for (Journal.Tag t : tags) {
                        if (t.isDeleted()) continue;
                        current.data.add(new DataRef(t.fsBlock, dataPos));
                        dataPos = (dataPos + 1) % n;
                    }
                    pos = dataPos;
                }
                case Journal.BT_REVOKE -> {
                    if (current != null) {
                        current.revokeIndex = pos;
                        current.revokedBlocks.addAll(Journal.parseRevokeList(block));
                    }
                    pos = (pos + 1) % n;
                }
                case Journal.BT_COMMIT -> {
                    if (current != null) current.commitIndex = pos;
                    pos = (pos + 1) % n;
                }
                default -> pos = (pos + 1) % n;
            }
        }
        if (current != null && !current.isComplete()
                && r.anomalies.stream().noneMatch(a -> a.journalIndex == current.descriptorIndex)) {
            r.anomalies.add(new Anomaly("anom:half-transaction@" + current.descriptorIndex,
                    "half-transaction", current.descriptorIndex,
                    "transaction seq " + current.sequence + " never reached a commit block"));
        }
        return r;
    }

    private static boolean allZero(byte[] b) {
        for (byte v : b) if (v != 0) return false;
        return true;
    }

    /**
     * Ring-order aware sequence comparison: returns true if `seq` falls inside
     * the closed range [fromSeq, toSeq] measured along the journal's write
     * direction (mod 2^32). Never a plain integer comparison.
     */
    public static boolean seqInRange(long seq, long fromSeq, long toSeq) {
        long span = (toSeq - fromSeq) & 0xFFFFFFFFL;
        long off = (seq - fromSeq) & 0xFFFFFFFFL;
        return off <= span;
    }

    /** Explanations of the machine-readable judgment rules, emitted as events. */
    public static Map<String, String> ruleJudgments(ScanResult r) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rule.only_committed_replayable",
                "仅有完整 commit 的事务可重放; complete=" + r.txns.stream().filter(Txn::isComplete).count()
                        + "/" + r.txns.size());
        m.put("rule.revoke_sequence_range",
                "revoke 按 sequence 范围生效: a revoke recorded in txn seq R suppresses copies written by txns with sequence in [.., R] along ring order");
        m.put("rule.ring_wrap_order",
                "环形日志序号回绕不用普通整数排序; ordering uses ring walk order, sequenceWrapped=" + r.sequenceWrapped);
        m.put("rule.checksum_boundary_uncertainty",
                "checksum 失败以后的边界保留不确定性; boundaryIndex=" + r.boundaryIndex
                        + (r.boundaryIndex >= 0 ? " reason=" + r.boundaryReason : ""));
        return m;
    }
}
