package ext4lab.journal;

import java.util.ArrayList;
import java.util.List;

/** Domain model records for the journal scan/replay pipeline. */
public final class Model {
    private Model() {}

    /** Raw superblock values are preserved verbatim; normalization happens in the derived layer. */
    public record Superblock(
            String rawBlockSize,        // raw representation as imported, never rewritten
            Integer blockSize,          // normalized, null when not convertible
            int journalStartBlock,      // ring origin (block index inside journal area)
            int journalBlocks,          // ring length in blocks
            long firstSeq,              // sequence of the oldest live transaction
            int checksumVersion,        // 2 or 3
            List<String> diagnostics    // e.g. invalid-block-size; raw value stays in place
    ) {
        public boolean usable() { return blockSize != null; }
    }

    public record Tag(long fsBlock, int flags, int csum16) {}

    /** One parsed journal block at a ring-relative offset. */
    public record JBlock(
            int ringOffset,             // offset from ring origin (coordinate base)
            int absIndex,               // absolute index inside journal area
            boolean validHeader,
            int type,                   // JournalFormat.TYPE_*
            long seq,
            List<Tag> tags,             // descriptor only
            List<Long> revokeBlocks,    // revoke only
            Long commitChecksum,        // commit only: stored checksum
            Long computedChecksum,      // commit only: recomputed
            boolean checksumOk,
            String note
    ) {
        public String id() { return "jb:" + absIndex; }
    }

    public record Txn(
            long seq,
            int startRingOffset,
            List<JBlock> descriptors,
            List<JBlock> dataBlocks,
            List<Long> revokeBlocks,
            JBlock commit,
            Status status,              // COMPLETE, HALF, STALE, UNCERTAIN
            String reason
    ) {
        public enum Status { COMPLETE, HALF, STALE, UNCERTAIN }
        public String id() { return "txn:" + seq; }
    }

    /** Machine-readable judgment: rule + subject + decision + basis. */
    public record Judgment(String rule, String subject, String decision, String basis) {}

    public record Anomaly(String kind, String subject, String detail, String sourceRef) {}

    public record ScanResult(
            Superblock sb,
            int crashAfter,             // only the first crashAfter ring blocks are visible
            List<JBlock> blocks,
            List<Txn> txns,
            List<Judgment> judgments,
            List<Anomaly> anomalies,
            Integer checksumBreakOffset, // ring offset where checksum failed; beyond = uncertain
            String stopReason
    ) {}

    /** One write of a filesystem block from the journal. */
    public record Write(long fsBlock, long txnSeq, int dataRingOffset, byte[] bytes) {}

    public record RevokeHit(long fsBlock, long revokeTxnSeq, long cancelledTxnSeq) {}

    /** A legal candidate completion when uncertainty exists. */
    public record Candidate(String id, String label, List<Long> includeTxnSeqs, boolean certain) {}

    public record ReplayPlan(
            String journal,
            List<Write> writes,             // ring order, committed only
            List<RevokeHit> revokeHits,
            List<Write> effectiveWrites,    // after revoke filtering, ring order
            List<Candidate> candidates,     // all legal candidates, incl. the certain prefix
            List<Judgment> judgments,
            List<Anomaly> anomalies
    ) {}
}
