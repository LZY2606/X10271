package jbd2lab.domain;

/**
 * 32-bit cyclic JBD2 transaction id (sequence number) comparison.
 * Ordinary integer sorting is wrong across a wrap: 0x00000000 is newer than 0xFFFFFFFF.
 * Forward distance uses modular arithmetic with a 2^31 half-space.
 */
public final class CyclicSeq {
    public static final long MOD = 1L << 32;
    public static final long HALF = 1L << 31;

    private CyclicSeq() {}

    /** Signed forward distance from {@code from} to {@code to}: positive means {@code to} is newer. */
    public static long forwardDistance(long from, long to) {
        long d = (to - from) & 0xffffffffL;
        if (d >= HALF) {
            d -= MOD;
        }
        return d;
    }

    /** True iff {@code candidate} is strictly newer than {@code older}. */
    public static boolean newerThan(long candidate, long older) {
        return forwardDistance(older, candidate) > 0;
    }

    /** True iff {@code a} is newer than or equal to {@code b} on the circle. */
    public static boolean newerOrEqual(long a, long b) {
        return a == b || newerThan(a, b);
    }

    public static long successor(long seq) {
        return (seq + 1) & 0xffffffffL;
    }

    public static String hex(long seq) {
        return String.format("0x%08x", seq);
    }
}
