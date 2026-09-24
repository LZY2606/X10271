package org.filesystem.journal.domain;

import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {
    }

    public record RawValue(String raw, String representation, String unit, String coordinateBasis) {
    }

    public record RawBlock(String id, String role, String media, int offset, int byteCount,
                           String coordinateBasis, String sha256) {
    }

    public record JournalNode(String id, String rawBlockId, int physicalSlot, int ringOrdinal,
                              String type, RawValue sequence, RawValue checksum,
                              String checksumVersionId) {
    }

    public record DescriptorTag(String id, String descriptorNodeId, int tagIndex,
                                RawValue targetFilesystemBlock, RawValue flags) {
    }

    public record DataRelation(String id, String dataNodeId, String descriptorTagId,
                               RawValue targetFilesystemBlock, int duplicateOrdinal) {
    }

    public record CommitRelation(String id, String commitNodeId, RawValue sequence,
                                 String transactionId) {
    }

    public record RevokeRelation(String id, String revokeNodeId, RawValue targetFilesystemBlock,
                                 RawValue firstSequence, RawValue lastSequence,
                                 RawValue rangeBasis) {
    }

    public record ChecksumVersion(String id, RawValue version, RawValue algorithm,
                                  RawValue trailerBytes, RawValue polynomial) {
    }

    public record SuperblockObservation(String id, String rawBlockId, RawValue blockSize,
                                        RawValue journalInode, RawValue totalBlocks,
                                        String diagnostic) {
    }

    public record CrashPoint(String id, RawValue afterNodeId, RawValue ringOrdinal,
                             RawValue coordinateBasis) {
    }

    public record SnapshotInput(String snapshotId, String name, RawValue blockSize,
                                RawValue ringStart, RawValue sequenceWidth,
                                CrashPoint crashPoint, ChecksumVersion checksumVersion,
                                List<RawBlock> blocks, List<JournalNode> nodes,
                                List<DescriptorTag> descriptorTags, List<DataRelation> dataRelations,
                                List<CommitRelation> commitRelations, List<RevokeRelation> revokeRelations,
                                List<SuperblockObservation> superblockObservations,
                                Map<String, String> sourceHashes) {
    }

    public record Event(String id, String projectId, long ordinal, String kind, String branch,
                        String parentId, String baseVersionId, String author, String createdAt,
                        String label, SnapshotInput snapshot, Map<String, Object> change,
                        String decisionCandidateId, Map<String, Object> metadata) {
    }
}
