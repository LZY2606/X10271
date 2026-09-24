package fsreplay.core;

import java.util.ArrayList;
import java.util.List;

/** Domain model. Every quantity keeps its raw representation plus unit/coordinate basis. */
public final class Model {
    private Model() {}

    /** A domain quantity: raw text as imported, unit/coordinate basis, normalized value (null if unconvertible). */
    public record Quantity(String raw, String unit, Long normalized, String diagnostic) {
        public static Quantity of(long value, String unit) {
            return new Quantity(Long.toString(value), unit, value, null);
        }
        public static Quantity raw(String raw, String unit) {
            try {
                long v = raw.startsWith("0x") || raw.startsWith("0X")
                        ? Long.parseUnsignedLong(raw.substring(2), 16)
                        : Long.parseLong(raw);
                return new Quantity(raw, unit, v, null);
            } catch (NumberFormatException e) {
                // unconvertible: stays in place with a diagnostic, never dropped
                return new Quantity(raw, unit, null, "unconvertible:" + raw);
            }
        }
    }

    public enum BlockKind { DESCRIPTOR, DATA, COMMIT, REVOKE, SUPERBLOCK, UNKNOWN }

    /** One parsed journal block. journalIndex is the ring coordinate (0-based, in journal blocks). */
    public record ParsedBlock(
            int journalIndex,
            BlockKind kind,
            long sequence,          // u32, wrap basis 2^32
            List<Long> fsBlocks,    // descriptor: target fs blocks; revoke: revoked fs blocks
            List<Long> tagChecksums,
            Long commitChecksum,    // null unless commit block
            boolean checksumOk,
            String note) {}

    public record SuperblockInfo(
            Quantity blockSize,        // unit: bytes
            Quantity journalLength,    // unit: journal blocks
            Quantity firstBlock,       // ring coordinate
            Quantity startBlock,       // ring coordinate (s_start)
            Quantity sequence,         // u32 wrap basis
            String checksumVersion,    // raw, e.g. "v3"
            List<String> diagnostics) {}

    /** A transaction group: descriptor + data blocks + optional commit + optional revoke. */
    public record TxnGroup(
            long sequence,
            List<ParsedBlock> descriptors,
            List<ParsedBlock> dataBlocks,
            ParsedBlock commit,          // null => half transaction (no complete commit)
            List<ParsedBlock> revokes,
            List<String> diagnostics) {
        public boolean committed() { return commit != null && commit.checksumOk(); }
    }

    /** Result of scanning the journal ring up to a crash point. */
    public record ScanResult(
            SuperblockInfo superblock,
            List<ParsedBlock> blocks,        // in ring scan order
            List<TxnGroup> transactions,     // wrap-aware sequence order
            Integer checksumBreakIndex,      // journal index where checksum failed, null if none
            List<String> diagnostics) {}

    /** One planned write: fsBlock <- journalBlock, ordered. */
    public record PlanStep(int order, long fsBlock, int journalIndex, long txnSequence,
                           boolean revoked, String reason) {}

    public record ReplayPlan(List<PlanStep> steps, List<String> diagnostics) {}

    /** Derived block map entry: fsBlock -> source journal block (or ORIGINAL). */
    public record MapEntry(long fsBlock, String sourceId, int journalIndex, long txnSequence,
                           boolean uncertain) {}

    public record DerivedMap(List<MapEntry> entries, Integer crashPoint, List<String> diagnostics) {}

    public static List<String> newDiags() { return new ArrayList<>(); }
}
