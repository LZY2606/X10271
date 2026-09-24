package org.fslab.journal.ring;

/**
 * JBD2 transaction sequence numbers live in an unsigned 32 bit ring.
 *
 * <p>They must never be ordered with ordinary integer comparison: after
 * {@code 0xffffffff} wraps to {@code 1}, a plain numeric sort would claim the
 * older transaction is newer. Ordering uses signed modular distance, the same
 * trick as TCP sequence numbers (and {@code tid_geq} style checks in JBD2).
 */
public record SequenceNum(long value) implements Comparable<SequenceNum> {

    public static final long MOD = 1L << 32;
    public static final SequenceNum FIRST = new SequenceNum(1);

    public SequenceNum {
        if (value < 1 || value > 0xffffffffL) {
            throw new IllegalArgumentException("sequence out of u32 ring: " + value);
        }
    }

    public static SequenceNum of(long value) {
        return new SequenceNum(value);
    }

    public SequenceNum next() {
        long n = value + 1;
        return new SequenceNum(n >= MOD ? 1 : n);
    }

    /** Signed forward distance from {@code this} to {@code other} along the ring. */
    public long forwardDistanceTo(SequenceNum other) {
        long d = other.value - value;
        if (d < 0) {
            d += MOD;
        }
        // Represent distances past the half-ring as negative (other is behind us).
        if (d > MOD / 2) {
            d -= MOD;
        }
        return d;
    }

    /** True when {@code other} is this sequence or ahead of it on the ring. */
    public boolean leq(SequenceNum other) {
        return forwardDistanceTo(other) >= 0;
    }

    public boolean lt(SequenceNum other) {
        return !equals(other) && leq(other);
    }

    @Override
    public int compareTo(SequenceNum o) {
        long d = forwardDistanceTo(o);
        return d == 0 ? 0 : d > 0 ? -1 : 1;
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(value);
    }
}
