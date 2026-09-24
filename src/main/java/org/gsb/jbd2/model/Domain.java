package org.gsb.jbd2.model;

import java.util.List;

public final class Domain {
    private Domain() {}

    public enum BlockKind {
        DESCRIPTOR, DATA, COMMIT, REVOKE, UNKNOWN
    }

    public enum ChecksumKind {
        NONE("none", 0), CRC32("crc32", 1), CRC32C("crc32c", 2);

        private final String wire;
        private final int versionCode;

        ChecksumKind(String wire, int versionCode) {
            this.wire = wire;
            this.versionCode = versionCode;
        }

        public String wire() { return wire; }
        public int versionCode() { return versionCode; }

        public static ChecksumKind fromWire(String value) {
            if (value == null || value.isBlank()) return NONE;
            for (ChecksumKind kind : values()) {
                if (kind.wire.equalsIgnoreCase(value) || String.valueOf(kind.versionCode).equals(value)) return kind;
            }
            throw new IllegalArgumentException("unsupported checksum kind: " + value);
        }
    }

    public record JournalBlock(
            long physicalIndex,
            BlockKind kind,
            long sequence,
            Integer checksumVersion,
            boolean checksumValid,
            Long targetBlock,
            Long revokeBlock,
            byte[] payload,
            byte[] raw,
            String diagnostic
    ) {
        public JournalBlock withChecksumInvalid(String reason) {
            return new JournalBlock(physicalIndex, kind, sequence, checksumVersion, false,
                    targetBlock, revokeBlock, payload, raw, reason);
        }
    }

    public record DescriptorEntry(long targetBlock, long dataPhysicalIndex, int order) {}

    public record RevokeEntry(long targetBlock, long fromSequence, long toSequence, int order) {}

    public record RawTransaction(
            long sequence,
            long descriptorPhysicalIndex,
            Long commitPhysicalIndex,
            Long revokePhysicalIndex,
            List<JournalBlock> blocks,
            List<DescriptorEntry> entries,
            List<RevokeEntry> revokeEntries,
            boolean complete,
            List<String> diagnostics
    ) {}

    public record ReplayAction(
            String stableId,
            long sequence,
            long targetBlock,
            long sourcePhysicalIndex,
            int order,
            String status,
            String reason
    ) {}

    public record ReplayCandidate(
            String stableId,
            String label,
            long firstUncertainPhysicalIndex,
            List<RawTransaction> transactions,
            List<ReplayAction> actions,
            List<String> diagnostics
    ) {}

    public record ScanResult(
            long journalStartPhysicalIndex,
            long ringLengthBlocks,
            Long crashPhysicalIndex,
            long entryPhysicalIndex,
            long scannedPhysicalIndex,
            List<JournalBlock> orderedBlocks,
            List<RawTransaction> transactions,
            List<ReplayCandidate> candidates,
            List<String> diagnostics,
            String orderBasis
    ) {}

    public record SourceSnapshot(
            String id,
            String label,
            String blockSizeRaw,
            Integer blockSizeBytes,
            String blockSizeUnit,
            String journalStartRaw,
            long journalStartBlock,
            String coordinateBasis,
            String crashPointRaw,
            Long crashPointBlock,
            String checksumVersionRaw,
            ChecksumKind checksumKind,
            String superblockJson,
            byte[] journalBytes,
            List<String> diagnostics
    ) {}

    public record GraphNode(
            String stableId,
            String kind,
            String label,
            String value,
            String unitOrCoordinate,
            String sourceIdentity,
            String state,
            String basis
    ) {}

    public record GraphEdge(String fromStableId, String toStableId, String relation) {}

    public record DerivedVersion(
            String eventId,
            String fingerprint,
            ScanResult scan,
            ReplayCandidate selectedCandidate,
            List<GraphNode> nodes,
            List<GraphEdge> edges,
            List<String> diagnostics
    ) {}
}
