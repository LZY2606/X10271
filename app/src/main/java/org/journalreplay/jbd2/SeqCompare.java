package org.journalreplay.jbd2;

/**
 * Wraparound-aware comparison of 32-bit JBD2 sequence numbers.
 *
 * <p>Journal sequence numbers live on a ring and wrap at 2^32, so plain integer
 * ordering is wrong: sequence 0 follows 0xFFFFFFFF. We use the same modular
 * distance rule as JBD2's tid_geq(): within a window of 2^31, {@code a} is
 * considered "after" {@code b} when the forward distance from b to a is the
 * smaller direction on the ring.
 */
public final class SeqCompare {
    private SeqCompare() {}

    private static final long MOD = 1L << 32;
    private static final long HALF = 1L << 31;

    /** Returns negative if a is before b on the ring, 0 if equal, positive if after. */
    public static int compare(long a, long b) {
        long forward = ((b - a) % MOD + MOD) % MOD; // distance from a to b going forward
        if (forward == 0) return 0;
        return forward < HALF ? -1 : 1;
    }

    /** True when a equals b or comes after b on the ring (JBD2 tid_geq). */
    public static boolean geq(long a, long b) {
        return compare(a, b) >= 0;
    }

    /** True when a comes strictly before b on the ring. */
    public static boolean before(long a, long b) {
        return compare(a, b) < 0;
    }
}
