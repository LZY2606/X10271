package org.fslab.jbd2.model;

import java.util.List;

/** Scanner verdict for one physical journal block. */
public record BlockScan(
        int physicalBlock,
        BlockType type,
        Long headerSequence,
        List<TagRecord> tags,
        List<Long> revokedFileBlocks,
        Long commitSequence,
        String commitChecksumHex,
        Boolean commitChecksumOk,
        List<Diagnostic> diagnostics) {

    public static BlockScan unknown(int physicalBlock, List<Diagnostic> diags) {
        return new BlockScan(physicalBlock, BlockType.UNKNOWN, null, List.of(), List.of(),
                null, null, null, diags);
    }
}
