package jbd2lab.engine;

import java.util.List;

/**
 * Final fs block derived by replay, with full provenance back to the journal
 * coordinate and the complete ordered write history that overwrote it.
 */
public record DerivedBlock(
        long fsBlock,
        WriteEvent source,
        String payloadSha,
        byte[] bytes,
        List<WriteEvent> history) {
}
