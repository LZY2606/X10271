package com.journalreplay.domain;

import java.util.List;

/**
 * One transaction group in ring order. Stable identity: "tx@" + sequence.
 * Only groups with status COMMITTED_VALID are replayable.
 */
public record TxGroup(
        String id,
        long seq,
        int firstLoc,
        List<Blocks.Descriptor> descriptors,
        List<Blocks.Revoke> revokes,
        Blocks.Commit commit,
        Status status,
        String statusReason) {

    public enum Status { COMMITTED_VALID, INCOMPLETE, CHECKSUM_FAILED }

    public boolean replayable() {
        return status == Status.COMMITTED_VALID;
    }

    /** (fsBlock, journalDataLoc) write intents in journal order. */
    public List<long[]> writeIntents() {
        List<long[]> out = new java.util.ArrayList<>();
        for (Blocks.Descriptor d : descriptors) {
            int dataLoc = d.dataStartLoc();
            for (Blocks.Tag t : d.tags()) {
                out.add(new long[]{t.fsBlock(), dataLoc});
                dataLoc++;
            }
        }
        return out;
    }
}
