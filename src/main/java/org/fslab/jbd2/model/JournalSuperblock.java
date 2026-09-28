package org.fslab.jbd2.model;

import java.util.List;

/**
 * Parsed journal superblock view. The lab understands the documented JBD2
 * superblock layout; unknown bytes stay preserved in the raw block and are
 * never silently dropped.
 */
public record JournalSuperblock(
        int physicalBlock,
        long magic,
        int blocktype,
        Q blockSize,
        Q totalBlocks,
        Q firstBlock,
        Q startSequence,
        long compatFeatures,
        long incompatFeatures,
        long roCompatFeatures,
        int checksumType,
        String uuidHex,
        String checksumHex,
        Boolean checksumOk,
        List<Diagnostic> diagnostics) {

    public boolean checksumsV2() {
        return checksumType == 2;
    }
}
