package org.jbd2lab.seq;

/**
 * 32-bit circular journal sequence numbers, per the JBD2 rule "wrap around is not ordinary
 * integer ordering".
 *
 * <p>{@code a} precedes {@code b} when the signed forward distance from {@code a} to {@code b}
 * in the 32-bit ring is positive and at most half the ring. Pairs further apart than half the
 * ring are not comparable from values alone — the caller must anchor them to physical ring
 * position (scanner does this), which is exactly why naive integer sort is forbidden.
 */
public final class CircularSeq {
    public static final long MOD = 1L << 32;
    public static final long HALF = MOD / 2;

    private CircularSeq() {}

    public static long norm(long v) {
        return v & 0xFFFFFFFFL;
    }

    /** Forward distance {@code to - from} on the ring, in {@code [0, 2^32)}. */
    public static long forwardDistance(long from, long to) {
        return (norm(to) - norm(from)) & 0xFFFFFFFFL;
    }

    /** True iff {@code a} strictly precedes {@code b} within half the ring forward. */
    public static boolean precedes(long a, long b) {
        long d = forwardDistance(a, b);
        return d != 0 && d < HALF;
    }

    /** -1, 0, 1 circular comparator; pairs over half-ring apart throw (not comparable). */
    public static int compare(long a, long b) {
        if (norm(a) == norm(b)) {
            return 0;
        }
        long d = forwardDistance(a, b);
        if (d < HALF) {
            return -1;
        }
        long r = forwardDistance(b, a);
        if (r < HALF) {
            return 1;
        }
        throw new ArithmeticException(
                "sequence values " + norm(a) + " and " + norm(b) + " are > half-ring apart; "
                        + "circular order is undecidable without a physical anchor");
    }

    public static long next(long v) {
        return norm(v + 1);
    }
}
