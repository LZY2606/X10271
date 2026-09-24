package org.fslab.journal.ring;

/**
 * Coordinate of one block inside the circular journal.
 *
 * @param index physical journal block index, 0 based; index 0 is the journal
 *              superblock and data never starts there
 * @param total number of blocks in the ring (including the superblock slot)
 */
public record RingPos(int index, int total) {

    public RingPos {
        if (total <= 1 || index < 0 || index >= total) {
            throw new IllegalArgumentException("bad ring position " + index + "/" + total);
        }
    }

    public static RingPos firstData(int total) {
        return new RingPos(1, total);
    }

    public RingPos advance(int steps) {
        int i = (index - 1 + steps) % (total - 1) + 1;
        return new RingPos(i, total);
    }

    public RingPos next() {
        return advance(1);
    }

    /** Forward block distance from this position to {@code other}, in [0,total-2]. */
    public int forwardDistanceTo(RingPos other) {
        int span = total - 1;
        int d = (other.index - index + span) % span;
        return d;
    }
}
