package jbd2lab.engine;

import jbd2lab.domain.BlockRef;

/** One (sequence, fsBlock) revocation read from a revoke block. */
public record RevokeEvent(
        long sequence,
        long fsBlock,
        BlockRef journalRef,
        int order) {
}
