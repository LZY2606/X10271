package jbd2lab.core;

/** A single revoked filesystem block coordinate asserted by a revoke block. */
public final class RevokeEntry {
    public final long fsBlock;
    public final int slot;             // physical ring slot of the revoke block
    public final int entryIndex;

    public RevokeEntry(long fsBlock, int slot, int entryIndex) {
        this.fsBlock = fsBlock;
        this.slot = slot;
        this.entryIndex = entryIndex;
    }
}
