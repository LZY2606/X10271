package lab.jbd2.model;

import java.util.List;

/** A parsed descriptor..data..(..)commit group as seen in the ring. */
public record JournalTxn(
        long descriptorPhysical,
        long commitPhysical,
        long sequence,
        boolean sequenceWrapped,
        long orderIndex,
        TxnStatus status,
        boolean descriptorChecksumOk,
        boolean commitChecksumOk,
        List<Tag> tags,
        List<Long> revokeFblocks,
        String diagnosis) {
}
