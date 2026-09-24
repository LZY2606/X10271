package com.fslab.jbd2.model;

import java.util.List;

public record ScannedTransaction(
        long sequence,
        long firstRingPos,
        Long descriptorRingPos,
        Long commitRingPos,
        List<Long> revokeRingPositions,
        List<DataBinding> bindings,
        List<Long> memberRingPositions,
        TxnStatus status,
        Long storedCommitChecksum,
        Long computedCommitChecksum,
        List<Diagnostic> diagnostics) {

    public static String id(long sequence) {
        return "txn@seq" + sequence;
    }

    public boolean replayableOnShape() {
        return status == TxnStatus.COMPLETE_VALID;
    }
}
