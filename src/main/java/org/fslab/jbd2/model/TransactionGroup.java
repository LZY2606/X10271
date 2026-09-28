package org.fslab.jbd2.model;

import java.util.List;

/**
 * A transaction group assembled in physical scan order. A group is REPLAYABLE
 * only when descriptor + commit are both present and every checksum validates
 * under the declared checksum version; everything else stays explicit.
 */
public record TransactionGroup(
        long sequence,
        Integer descriptorBlock,
        List<TagRecord> tags,
        List<DataRef> dataRefs,
        List<Integer> revokeBlocks,
        List<Long> revokedFileBlocks,
        Integer commitBlock,
        boolean committed,
        boolean checksumOk,
        List<Integer> memberBlocks,
        List<Diagnostic> diagnostics) {

    public boolean replayable() {
        return committed && checksumOk && descriptorBlock != null;
    }

    /** A data block and the tag that carries it, in descriptor order. */
    public record DataRef(TagRecord tag, int dataPhysicalBlock) {}
}
