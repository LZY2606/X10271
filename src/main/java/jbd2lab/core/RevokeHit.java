package jbd2lab.core;

/** A revoke assertion and the write it suppresses (if any), with range justification. */
public final class RevokeHit {
    public final long fsBlock;
    public final long revokerSeq;
    public final int revokeSlot;
    public final long writeSeq;
    public final int dataSlot;
    public final long circularDelta;    // revoker - write on the circular axis (>= 0 => in range)
    public final boolean inRange;

    public RevokeHit(long fsBlock, long revokerSeq, int revokeSlot, long writeSeq,
                     int dataSlot, long circularDelta, boolean inRange) {
        this.fsBlock = fsBlock;
        this.revokerSeq = revokerSeq;
        this.revokeSlot = revokeSlot;
        this.writeSeq = writeSeq;
        this.dataSlot = dataSlot;
        this.circularDelta = circularDelta;
        this.inRange = inRange;
    }

    public String stableId() {
        return "revoke:fs=" + fsBlock + ":r=" + revokerSeq + ":w=" + writeSeq;
    }
}
