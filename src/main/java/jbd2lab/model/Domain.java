package jbd2lab.model;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {
    }

    public static final int JBD2_MAGIC = 0xc03b3998;
    public static final int DESCRIPTOR = 1;
    public static final int COMMIT = 2;
    public static final int REVOKE = 3;
    public static final int DATA = 5;
    public static final int HEADER_SIZE = 16;
    public static final int CHECKSUM_SIZE = 4;

    public record Quantity(String raw, String unit, Long value, String coordinateBasis, String diagnostic) {
        public static Quantity parsed(String raw, String unit, long value, String coordinateBasis) {
            return new Quantity(raw, unit, value, coordinateBasis, null);
        }

        public static Quantity invalid(String raw, String unit, String diagnostic, String coordinateBasis) {
            return new Quantity(raw, unit, null, coordinateBasis, diagnostic);
        }
    }

    public record RawBlock(
            String id,
            String role,
            Integer ringSlot,
            Long filesystemBlock,
            String bytesHex,
            String sourceUri,
            Map<String, String> annotations
    ) {
        public byte[] bytes() {
            return HexFormat.of().parseHex(bytesHex);
        }
    }

    public record SuperblockMaterial(
            String id,
            String bytesHex,
            Quantity blockSize,
            long journalStartFilesystemBlock,
            String checksumAlgorithm,
            String checksumVersion,
            List<String> diagnostics
    ) {
    }

    public record DescriptorTag(long targetFilesystemBlock, long flags, int payloadOffset) {
    }

    public record JournalBlock(
            String rawBlockId,
            int ringSlot,
            int type,
            long sequence,
            int storedChecksum,
            int calculatedChecksum,
            boolean checksumValid,
            boolean magicValid,
            byte[] payload,
            List<DescriptorTag> descriptorTags,
            List<Long> revokedBlocks,
            List<String> diagnostics
    ) {
    }

    public record CrashPoint(String id, int observedThroughRingSlot, String afterByteRaw, String unit, String coordinateBasis) {
    }

    public record RevokeHit(
            String id,
            long revokeSequence,
            long writeSequence,
            long filesystemBlock,
            String dataBlockId,
            String revokeBlockId,
            String reasonRuleId
    ) {
    }

    public record CoverageStep(
            String id,
            int order,
            long sequence,
            long filesystemBlock,
            String dataBlockId,
            String transactionId,
            boolean supersededWithinTransaction,
            boolean revoked
    ) {
    }

    public record ReplayOperation(
            String id,
            int order,
            long sequence,
            long filesystemBlock,
            String sourceBlockId,
            String sourceDigestHex,
            String firstApplicationDigestHex,
            String secondApplicationDigestHex,
            boolean secondPassChanged,
            List<String> provenance
    ) {
    }

    public record FrontierCandidate(
            String id,
            String name,
            boolean selectedByDefault,
            boolean replayAfterChecksumFailure,
            String rationale,
            List<String> replayedTransactionIds,
            List<String> ruleIds
    ) {
    }

    public record TransactionView(
            String id,
            long sequence,
            String state,
            boolean complete,
            boolean checksumValid,
            boolean replayable,
            int firstSlot,
            int lastSlot,
            List<String> blockIds,
            List<String> diagnostics,
            List<String> ruleIds
    ) {
    }

    public record DerivedBlock(
            String id,
            long filesystemBlock,
            String state,
            String winningBlockId,
            Long winningSequence,
            List<String> candidateBlockIds,
            List<String> sourceChain,
            String digestHex
    ) {
    }

    public record DerivedGraph(
            List<JournalBlock> blocks,
            List<TransactionView> transactions,
            List<CoverageStep> coverage,
            List<RevokeHit> revokeHits,
            List<ReplayOperation> replayOperations,
            List<DerivedBlock> derivedBlocks,
            List<FrontierCandidate> frontierCandidates,
            String selectedFrontierCandidateId,
            List<String> diagnostics
    ) {
    }

    public record Snapshot(
            String id,
            String name,
            SuperblockMaterial superblock,
            List<RawBlock> blocks,
            List<RawBlock> probes,
            CrashPoint crashPoint
    ) {
    }
}
