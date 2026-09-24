package jbd2lab.domain;

import java.util.List;

/** Result of scanning the raw journal ring: per-block parse views and ring geometry diagnostics. */
public record Scan(
        List<JournalBlock> blocks,
        int ringStartAbs,
        int ringCapacityBlocks,
        List<String> diagnostics
) {}
