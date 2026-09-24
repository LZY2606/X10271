package jbd2lab.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A reconstructed transaction candidate. A transaction is replayable only when it has a
 * descriptor, all referenced data blocks and a COMMIT, and every asserted checksum
 * matches under the journal superblock's checksum version. Half transactions are kept
 * with {@code complete=false}; checksum failures keep the boundary uncertain (see
 * {@code checksumBroken}) rather than silently dropping later material.
 */
public final class Txn {
    public final long sequence;
    public final int descSlot;
    public Integer commitSlot;
    public final List<TagRec> tags = new ArrayList<>();
    public final List<RevokeEntry> revokes = new ArrayList<>();
    public final List<Integer> revokeSlots = new ArrayList<>();
    public boolean complete;
    public boolean truncated;                 // ring/scan ended before commit (half transaction)
    public boolean descChecksumOk = true;
    public boolean commitChecksumOk = true;
    public final List<String> tagCrcFailures = new ArrayList<>();
    /** first slot belonging to this txn; last slot (exclusive-ish) tracked by endSlot */
    public int endSlot;
    /** true when the scan desynced immediately after this txn due to corruption */
    public boolean boundaryUncertain;

    public Txn(long sequence, int descSlot) {
        this.sequence = sequence;
        this.descSlot = descSlot;
        this.endSlot = descSlot;
    }

    public boolean checksumOk() {
        return descChecksumOk && commitChecksumOk && tagCrcFailures.isEmpty();
    }

    public boolean replayable() { return complete && checksumOk(); }

    public String status() {
        if (!complete) return truncated ? "half_truncated" : "half_no_commit";
        if (!checksumOk()) return "checksum_failed";
        if (boundaryUncertain) return "committed_boundary_uncertain";
        return "committed";
    }
}
