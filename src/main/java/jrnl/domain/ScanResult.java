package jrnl.domain;

import java.util.List;

/** Result of walking the physical ring from its start exactly once. */
public record ScanResult(
        RingPos ringStart,
        int ringSlots,
        Seq anchorSeq,
        List<ScanBlock> blocks,
        List<TxnGroup> txns,
        List<String> diagnostics) {
}
