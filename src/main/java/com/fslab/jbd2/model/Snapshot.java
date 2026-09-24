package com.fslab.jbd2.model;

import java.util.List;

/**
 * Import unit: raw ext4/JBD2 journal blocks plus the journal superblock and the
 * reviewer's expected crash point. Every quantity keeps its coordinate basis.
 */
public record Snapshot(
        String snapshotId,
        String label,
        Quantity blockSize,
        Quantity ringStart,
        Quantity maxLen,
        Quantity checksumVersion,
        Quantity expectedCrashAfterRing,
        SuperblockInfo superblock,
        List<JournalBlock> journalBlocks,
        List<Diagnostic> importDiagnostics) {

    public static String id(String snapshotId) {
        return "snap:" + snapshotId;
    }
}
