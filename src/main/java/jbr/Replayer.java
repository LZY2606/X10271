package jbr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays scanned transactions up to a crash point (journal-block-index).
 * Semantics:
 *  - only transactions with a complete commit are replayable;
 *  - revoke applies by sequence range: a revoke recorded in txn R suppresses
 *    copies of the listed fs blocks written by txns at ring order <= R's;
 *  - ordering follows the ring walk, never integer sorting of sequences;
 *  - a checksum/invalid-block boundary keeps everything past it uncertain:
 *    those transactions are returned as candidates, not applied;
 *  - replay is a pure function of (journal, crashPoint) => idempotent.
 */
public final class Replayer {

    public static final class BlockValue {
        public final long fsBlock;
        public final long txnSeq;
        public final int journalIndex;
        public final String bytesHash;
        public BlockValue(long fsBlock, long txnSeq, int journalIndex, String bytesHash) {
            this.fsBlock = fsBlock;
            this.txnSeq = txnSeq;
            this.journalIndex = journalIndex;
            this.bytesHash = bytesHash;
        }
    }

    public static final class RevokeHit {
        public final long fsBlock;
        public final long victimTxnSeq;
        public final long revokeTxnSeq;
        public RevokeHit(long fsBlock, long victimTxnSeq, long revokeTxnSeq) {
            this.fsBlock = fsBlock;
            this.victimTxnSeq = victimTxnSeq;
            this.revokeTxnSeq = revokeTxnSeq;
        }
    }

    public static final class Candidate {
        public final String id;
        public final String reason;
        public final long txnSeq;
        public Candidate(String id, String reason, long txnSeq) {
            this.id = id;
            this.reason = reason;
            this.txnSeq = txnSeq;
        }
    }

    public static final class ReplayResult {
        public final int crashPoint;
        /** fs block -> winning copy, insertion order = first-write order. */
        public final Map<Long, BlockValue> blockMap = new LinkedHashMap<>();
        /** fs block -> ordered list of writes (including revoked/skipped), for overwrite order display. */
        public final Map<Long, List<String>> overwriteOrder = new LinkedHashMap<>();
        public final List<RevokeHit> revokeHits = new ArrayList<>();
        /** Transactions kept out of the certain result: half transactions and post-boundary tail. */
        public final List<Candidate> candidates = new ArrayList<>();
        public final List<String> replayedTxns = new ArrayList<>();
        public boolean uncertainTail;

        public ReplayResult(int crashPoint) { this.crashPoint = crashPoint; }

        /** Stable fingerprint used by the idempotency check. */
        public String fingerprint() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Long, BlockValue> e : blockMap.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue().bytesHash)
                        .append('@').append(e.getValue().txnSeq).append(';');
            }
            return sb.toString();
        }
    }

    public ReplayResult replay(Journal j, Scanner.ScanResult scan, int crashPoint) {
        ReplayResult r = new ReplayResult(crashPoint);
        // ring position of each journal index, for "reached before crash point"
        Map<Integer, Integer> ringPos = new HashMapCompat.positions(scan.walkOrder);
        int crashPos = ringPos.getOrDefault(crashPoint, Integer.MAX_VALUE);

        // Partition transactions: certain (complete, commit before crash, before boundary)
        // vs candidates (half transactions, or anything at/after the boundary).
        List<Scanner.Txn> certain = new ArrayList<>();
        for (Scanner.Txn t : scan.txns) {
            boolean pastBoundary = scan.boundaryIndex >= 0
                    && ringPos.getOrDefault(t.descriptorIndex, 0) >= ringPos.get(scan.boundaryIndex);
            if (pastBoundary) {
                r.uncertainTail = true;
                r.candidates.add(new Candidate("cand:txn:" + t.sequence,
                        "after " + scan.boundaryReason + " boundary at block " + scan.boundaryIndex
                                + "; kept as candidate, not applied", t.sequence));
                continue;
            }
            if (!t.isComplete()) {
                r.candidates.add(new Candidate("cand:txn:" + t.sequence,
                        "half transaction: descriptor/data present but no commit", t.sequence));
                continue;
            }
            int commitPos = ringPos.getOrDefault(t.commitIndex, Integer.MAX_VALUE);
            if (commitPos > crashPos) {
                r.candidates.add(new Candidate("cand:txn:" + t.sequence,
                        "commit at block " + t.commitIndex + " is past crash point " + crashPoint, t.sequence));
                continue;
            }
            certain.add(t);
        }

        // Revoke sets by sequence range along ring order: a revoke in txn R
        // suppresses copies from txns with ringOrder <= R.ringOrder.
        for (Scanner.Txn t : certain) {
            r.replayedTxns.add(t.id());
            for (Scanner.DataRef d : t.data) {
                Scanner.Txn revoker = null;
                for (Scanner.Txn other : certain) {
                    if (other.ringOrder >= t.ringOrder && other.revokedBlocks.contains(d.fsBlock)) {
                        revoker = other; // latest applicable revoke wins
                    }
                }
                String writeDesc = "txn:" + t.sequence + "@" + d.journalIndex;
                r.overwriteOrder.computeIfAbsent(d.fsBlock, k -> new ArrayList<>()).add(writeDesc);
                if (revoker != null) {
                    r.revokeHits.add(new RevokeHit(d.fsBlock, t.sequence, revoker.sequence));
                    continue;
                }
                byte[] bytes = j.blocks.get(d.journalIndex);
                String hash = Integer.toHexString(Crc32c.compute(bytes));
                r.blockMap.put(d.fsBlock, new BlockValue(d.fsBlock, t.sequence, d.journalIndex, hash));
            }
        }
        return r;
    }

    /** Small helper to avoid a static import dance. */
    private static final class HashMapCompat {
        static Map<Integer, Integer> positions(List<Integer> walkOrder) {
            Map<Integer, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < walkOrder.size(); i++) m.put(walkOrder.get(i), i);
            return m;
        }
    }
}
