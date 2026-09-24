package jrnl.domain;

import java.util.List;

/**
 * The full imported source snapshot. Every field here is an original input with
 * its raw representation preserved; normalisation happens only in the derived
 * layer ({@code jrnl.derive}).
 */
public record Snapshot(
        String caseId,
        List<RawBlock> journalBlocks,
        RingStart ringStart,
        BlockSize blockSize,
        ChecksumVersion checksumVersion,
        String checksumRaw,
        List<CrashPoint> crashPoints,
        List<String> diagnostics) {

    /** Ring start coordinate with both raw and derived representations. */
    public record RingStart(int imageIndex, String rawRepr) {
    }
}
