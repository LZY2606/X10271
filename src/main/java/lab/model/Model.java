package lab.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Domain model for the ext4/JBD2 journal replay lab. */
public final class Model {
    private Model() {}

    /**
     * A domain quantity. The raw representation is always preserved at import;
     * normalization happens only in the derived layer. Unconvertible values keep
     * normalized == null and carry a diagnostic.
     */
    public record Quantity(String raw, String unit, String basis, Double normalized, String diagnostic) {
        public static Quantity raw(String raw, String unit, String basis) {
            return new Quantity(raw, unit, basis, null, null);
        }
    }

    /** One physical journal block inside the ring. */
    public record JBlock(int slot, String type, long sequence,
                         List<Long> tags, List<Long> revoke,
                         String payloadHex, String checksumStored, String checksumVersion,
                         boolean checksumOk) {
        public String canonical() {
            String tagCsv = tags == null ? "" : joinLongs(tags);
            String revCsv = revoke == null ? "" : joinLongs(revoke);
            String payload = payloadHex == null ? "" : payloadHex;
            return type + "|" + sequence + "|" + tagCsv + "|" + revCsv + "|" + payload;
        }

        static String joinLongs(List<Long> xs) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < xs.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(xs.get(i));
            }
            return sb.toString();
        }
    }

    /** Journal image as imported: original block bytes, block size, ring start,
     *  sequences, descriptor/data/commit/revoke relations and checksum version. */
    public record JournalSpec(String id, Quantity blockSize, Quantity ringStart, int ringBlocks,
                              long sequenceBase, String checksumVersion, List<JBlock> blocks,
                              Map<String, Quantity> extraQuantities) {}

    /** A single logical write recovered from descriptor tags + following data blocks. */
    public record Write(long fsblock, String payloadHex, long sequence, int slot) {}

    /** A transaction group inside the ring. */
    public static final class Txn {
        public final long sequence;
        public final int ringPos;          // position in ring order from ring start
        public final int startSlot;
        public final List<JBlock> blocks = new ArrayList<>();
        public final List<Write> writes = new ArrayList<>();
        public final List<Long> revokes = new ArrayList<>();
        public boolean hasCommit;
        public boolean checksumOk = true;
        public String state = "PENDING";   // REPLAYABLE | NO_COMMIT | CHECKSUM_BREAK | BEYOND_CRASH | UNCERTAIN

        public Txn(long sequence, int ringPos, int startSlot) {
            this.sequence = sequence;
            this.ringPos = ringPos;
            this.startSlot = startSlot;
        }
    }

    /** A kept candidate block map when the plan cannot be uniquely decided. */
    public record Candidate(String id, String label, List<Long> includedSequences,
                            LinkedHashMap<Long, String> blockMap, boolean chosen) {}

    /** A revoke hit: fsblock revoked from an earlier sequence by a later one. */
    public record RevokeHit(long fsblock, long revokedFromSequence, long revokedBySequence) {}

    /** Overwrite record: a later write replacing an earlier one for the same fsblock. */
    public record Overwrite(long fsblock, long fromSequence, long toSequence) {}

    /** The full explainable replay plan. */
    public static final class ReplayPlan {
        public String journalId;
        public Long crashAtSlot;
        public final List<String> diagnostics = new ArrayList<>();
        public final List<Txn> txns = new ArrayList<>();          // ring order
        public final List<Txn> replayable = new ArrayList<>();    // certain prefix
        public final List<Write> appliedWrites = new ArrayList<>();
        public final List<RevokeHit> revokeHits = new ArrayList<>();
        public final List<Overwrite> overwrites = new ArrayList<>();
        public final LinkedHashMap<Long, String> blockMap = new LinkedHashMap<>();
        public final List<Candidate> candidates = new ArrayList<>();
        public final List<Map<String, Object>> events = new ArrayList<>();
        public Long checksumBreakAtSequence;
        public boolean blockSizeValid = true;
        public boolean idempotent;
    }
}
