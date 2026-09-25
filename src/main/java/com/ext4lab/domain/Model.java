package com.ext4lab.domain;

import java.util.List;
import java.util.Map;

/** Domain records. Raw imported values are always preserved; normalization lives in derived artifacts. */
public final class Model {
    private Model() {}

    public enum BlockType { DESCRIPTOR, DATA, COMMIT, REVOKE, SUPERBLOCK, UNKNOWN }

    public enum Status { CERTAIN, UNCERTAIN, SUPERSEDED, REVOKED, ADOPTED, REJECTED }

    public enum AnomalyKind {
        HALF_TRANSACTION, CHECKSUM_BREAK, INVALID_BLOCK_SIZE, STRAY_COMMIT, HALF_WRITE, RING_WRAP
    }

    /** Raw superblock values, exactly as imported. Every quantity carries its unit/coordinate basis. */
    public record Superblock(long blockSizeBytes, long journalStartBlock, long startSequence,
                             int checksumVersion, int ringBlocks) {
        public boolean blockSizeValid() {
            return blockSizeBytes >= 1024 && blockSizeBytes <= 65536
                    && (blockSizeBytes & (blockSizeBytes - 1)) == 0;
        }
    }

    /** One raw journal ring block; raw bytes are never rewritten after import. */
    public record JournalBlock(int ringIndex, long sequence, BlockType type, byte[] raw) {
        public String id() {
            return "JB#" + ringIndex;
        }
    }

    public record Tag(long fsBlock, int flags, int crc16) {}

    public record DataEntry(int ringIndex, long fsBlock, byte[] bytes) {}

    public record Txn(long sequence, List<DataEntry> data, boolean committed, int commitRingIndex) {
        public String id() {
            return "TXN#" + SeqNo.hex(sequence);
        }
    }

    public record Revoke(long sequence, int ringIndex, List<Long> fsBlocks) {
        public String id() {
            return "REVOKE@JB#" + ringIndex;
        }
    }

    public record Anomaly(AnomalyKind kind, int ringIndex, long sequence, String detail,
                          List<String> affected) {}

    public record ScanResult(Superblock superblock, List<JournalBlock> ring, int crashIndex,
                             List<Txn> txns, List<Revoke> revokes, List<Anomaly> anomalies,
                             Integer uncertaintyBoundary) {}

    /** One planned overwrite of a filesystem block, with provenance and status. */
    public record Overwrite(long fsBlock, int journalRingIndex, long txnSeq, Status status, String reason) {}

    public record RevokeHit(long fsBlock, long revokeSeq, long victimTxnSeq, int revokeRingIndex) {}

    /** A legal candidate final-state map. Uncertainty keeps all candidates; none is dropped. */
    public record Candidate(String label, Map<Long, Overwrite> map, boolean adopted, String rationale) {}

    public record ReplayPlan(List<Overwrite> orderedWrites, List<RevokeHit> revokeHits,
                             List<Candidate> candidates, List<Anomaly> anomalies) {}
}
