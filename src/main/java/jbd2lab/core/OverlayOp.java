package jbd2lab.core;

/**
 * One replayable write (or suppressed write) against a filesystem block coordinate.
 * Provenance is explicit: descriptor slot/tag index -> data slot -> commit slot.
 */
public final class OverlayOp {
    public final long fsBlock;
    public final long sequence;
    public final int order;             // replay order index (circular-axis order)
    public final int descSlot;
    public final int dataSlot;
    public final Integer commitSlot;
    public final int tagIndex;
    public final String blockSha;
    public boolean suppressedByRevoke;
    public Long suppressorSeq;          // sequence of the committed transaction asserting revoke
    public Integer suppressorSlot;
    public String decisionNote;

    public OverlayOp(long fsBlock, long sequence, int order, int descSlot, int dataSlot,
                     Integer commitSlot, int tagIndex, String blockSha) {
        this.fsBlock = fsBlock;
        this.sequence = sequence;
        this.order = order;
        this.descSlot = descSlot;
        this.dataSlot = dataSlot;
        this.commitSlot = commitSlot;
        this.tagIndex = tagIndex;
        this.blockSha = blockSha;
    }

    public String stableId() {
        return "op:seq=" + sequence + ":fs=" + fsBlock + ":tag=" + tagIndex
                + ":data=" + dataSlot;
    }
}
