package jbd2lab.core;

/**
 * JBD2 transaction sequences are an unsigned 32-bit ring. Ordering must never be done by
 * plain integer comparison after wrap: within one epoch window (half the ring) we compare
 * on the unwrapped axis, rotating so that the wrap boundary is not mistaken for a jump
 * backwards. {@code epoch} disambiguates full-ring laps when the scanner sees them.
 */
public final class RingSeq {
    private RingSeq() {}

    public static final long MOD = 1L << 32;
    public static final long HALF = 1L << 31;

    /** Signed circular delta: a relative to b, in (-HALF, HALF]. */
    public static long delta(long a, long b) {
        long d = (a - b) % MOD;
        if (d <= -HALF) d += MOD;
        if (d > HALF) d -= MOD;
        return d;
    }

    /** a is after b on the ring (strictly), using the circular axis anchored at b. */
    public static boolean after(long a, long b) { return delta(a, b) > 0; }
    public static boolean atOrAfter(long a, long b) { return delta(a, b) >= 0; }
    public static boolean before(long a, long b) { return delta(a, b) < 0; }

    public static long next(long s) { return (s + 1) % MOD; }
}
