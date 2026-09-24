package jrnl.domain;

import java.util.List;

/**
 * A transaction group reconstructed from the ring.
 * Status: COMPLETE only when a descriptor (or revoke block) is matched by a
 * matching-sequence commit with an OK checksum.
 */
public record TxnGroup(
        Seq sequence,
        List<RingPos> memberPos,
        RingPos descriptorPos,
        RingPos commitPos,
        List<DataWrite> writes,
        List<Long> revokes,
        String status,
        List<String> diagnostics) {

    public boolean complete() {
        return "COMPLETE".equals(status);
    }

    /** One mapped data block inside a descriptor transaction. */
    public record DataWrite(RingPos dataPos, long fsBlock, DescriptorTag tag) {
    }
}
