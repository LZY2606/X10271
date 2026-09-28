package org.fslab.jbd2.model;

import java.util.List;

/** Full scan of a journal image: ring geometry, per-block verdicts, groups. */
public record ScanResult(
        JournalSuperblock superblock,
        Q ringStart,
        Q observedBlocks,
        List<BlockScan> blockScans,
        List<TransactionGroup> groupsInPhysicalOrder,
        List<Long> sequenceOrder,
        List<Diagnostic> diagnostics) {}
