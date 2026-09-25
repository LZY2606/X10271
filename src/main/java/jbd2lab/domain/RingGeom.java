package jbd2lab.domain;

/**
 * Geometry and origin of the journal ring. Coordinates are physical block indices
 * inside the journal image. {@code first} is the ring start block (journal block 1
 * in JBD2 terms, right after the superblock), {@code total} is the usable ring
 * length. A physical coordinate maps with {@code first + (offset mod total)}.
 */
public record RingGeom(Quantity blockSize, int first, int total) {

    public boolean blockSizeValid() {
        if (!blockSize.valid()) {
            return false;
        }
        long bs = blockSize.normalized();
        return bs >= 512 && bs <= (64 * 1024) && Long.bitCount(bs) == 1;
    }

    public int physicalFor(long ringOffset) {
        if (total <= 0) {
            throw new IllegalStateException("ring total must be positive");
        }
        long t = ringOffset % total;
        if (t < 0) {
            t += total;
        }
        return first + (int) t;
    }

    public int physicalCount() {
        return first + total;
    }
}
