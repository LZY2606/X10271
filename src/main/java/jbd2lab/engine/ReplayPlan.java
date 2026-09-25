package jbd2lab.engine;

import java.util.List;

import jbd2lab.domain.TransactionGroup;

/**
 * A replay plan. The deterministic plan has {@code uncertain=false} and covers
 * only committed, checksum-valid groups before the first checksum-failure
 * boundary and at/before the crash point. Every replayable group beyond the
 * boundary is preserved in {@code candidates} as an equally legal alternative
 * plan; none are merged to force a single answer.
 */
public record ReplayPlan(
        String planId,
        boolean uncertain,
        boolean crashTruncated,
        long crashPoint,
        List<WriteEvent> writes,
        List<RevokeEvent> revokes,
        List<RevokeHit> revokeHits,
        List<DerivedBlock> derivedBlocks,
        List<TransactionGroup> candidateGroups,
        List<ReplayPlan> candidates,
        String basis) {

    public List<Long> derivedFingerprint() {
        return derivedBlocks.stream().map(DerivedBlock::fsBlock).toList();
    }
}
