package jbd2lab.domain;

import java.util.List;
import java.util.Map;

/**
 * Result of scanning a journal ring from its start:
 *  - blocks:   parse state per physical block (stable display rows)
 *  - groups:   assembled transaction groups in ring order
 *  - boundary: first ring offset after which contents are uncertain
 *              (checksum failure / torn transaction), or -1
 *  - diagnostics: unconvertible inputs and structural problems
 */
public record ScanResult(
        RingGeom geom,
        int checksumVersion,
        List<ParsedBlock> blocks,
        List<TransactionGroup> groups,
        long boundaryOffset,
        boolean truncatedHalfTransaction,
        List<String> diagnostics) {

    public Map<Long, TransactionGroup> bySequence() {
        java.util.LinkedHashMap<Long, TransactionGroup> m = new java.util.LinkedHashMap<>();
        for (TransactionGroup g : groups) {
            m.put(g.sequence(), g);
        }
        return m;
    }
}
