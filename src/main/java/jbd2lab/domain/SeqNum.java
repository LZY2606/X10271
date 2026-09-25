package jbd2lab.domain;

/**
 * 32-bit journal sequence numbers with ring semantics. Ordering must never use
 * plain integer comparison after a wrap; comparisons go through modular
 * difference relative to a reference point on the ring.
 */
public final class SeqNum {
    public static final long MOD = 1L << 32;
    public static final long FIRST = 1L;

    private SeqNum() {
    }

    /** Signed modular distance (a - b) on the uint32 ring, in (-2^31, 2^31]. */
    public static long diff(long a, long b) {
        long d = (a - b) % MOD;
        if (d > Integer.MAX_VALUE) {
            d -= MOD;
        }
        if (d <= Integer.MIN_VALUE) {
            d += MOD;
        }
        return d;
    }

    /** True when {@code a} is strictly after {@code b} on the ring from b's viewpoint. */
    public static boolean after(long a, long b) {
        return diff(a, b) > 0;
    }

    /** True when {@code a} is at or after {@code b} on the ring. */
    public static boolean atOrAfter(long a, long b) {
        return diff(a, b) >= 0;
    }

    public static long next(long s) {
        return (s + 1) % MOD;
    }

    public static long mask(long s) {
        return s & 0xFFFFFFFFL;
    }
}
