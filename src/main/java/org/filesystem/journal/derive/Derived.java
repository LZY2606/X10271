package org.filesystem.journal.derive;

import java.util.List;
import java.util.Map;

public final class Derived {
    private Derived() {
    }

    public record SourceRef(String sourceId, String sourceKind, String role) {
    }

    public record DisplayValue(String stableId, String label, String value, String unit,
                               String coordinateBasis, String rawValue, String status,
                               List<SourceRef> sources, String reason) {
    }

    public record Issue(String stableId, String severity, String code, String title,
                        String status, String atNodeId, String detail,
                        List<SourceRef> sources, List<String> candidateIds) {
    }

    public record BlockWrite(String stableId, String transactionId, String targetBlock,
                             String targetRaw, String dataNodeId, String descriptorTagId,
                             int ringOrdinal, int coverageOrder, String status,
                             List<SourceRef> sources) {
    }

    public record TransactionGroup(String stableId, String sequenceRaw, long sequence,
                                   int firstRingOrdinal, String status, boolean completeCommit,
                                   boolean commitChecksumValid, List<String> memberNodeIds,
                                   List<BlockWrite> writes, List<SourceRef> sources,
                                   String reason) {
    }

    public record RevokeHit(String stableId, String revokeId, String targetBlock,
                            String rangeFirstRaw, String rangeLastRaw, String hitWriteId,
                            boolean inSequenceRange, boolean beforeRevokeInRing,
                            boolean hit, String status, List<SourceRef> sources, String reason) {
    }

    public record ReplayStep(String stableId, int order, String action, String targetBlock,
                             String sourceWriteId, String transactionId, String status,
                             String reason, List<SourceRef> sources) {
    }

    public record ReplayCandidate(String stableId, String title, String policy, boolean adopted,
                                  String status, String crashNodeId,
                                  List<String> eligibleTransactionIds,
                                  List<String> blockedTransactionIds,
                                  List<ReplayStep> steps, List<SourceRef> sources,
                                  String reason) {
    }

    public record ResultState(String stableId, String kind, String fingerprint, String state,
                              String reason, List<SourceRef> dependencies) {
    }

    public record DerivedSnapshot(String version, String fingerprint,
                                  List<DisplayValue> facts,
                                  List<TransactionGroup> transactions,
                                  List<BlockWrite> coverage,
                                  List<RevokeHit> revokeHits,
                                  List<ReplayCandidate> candidates,
                                  List<Issue> issues,
                                  List<ResultState> dependencyResults,
                                  Map<String, Object> machineEvents) {
    }
}
