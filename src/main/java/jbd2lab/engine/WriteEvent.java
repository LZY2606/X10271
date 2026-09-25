package jbd2lab.engine;

import jbd2lab.domain.BlockRef;

/** One descriptor/data write of an fs block inside a committed transaction. */
public record WriteEvent(
        long sequence,
        long fsBlock,
        BlockRef journalRef,
        String payloadSha,
        int order) {
}
