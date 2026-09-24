package journal.core;

/**
 * Ordering for 32-bit JBD2 sequence numbers on a circular log.
 *
 * Sequence numbers wrap at 2^32, so ordinary integer sorting is wrong once a
 * wrap is visible. All comparisons are taken relative to an anchor sequence
 * (the first sequence observed at the ring start): a sequence within the
 * forward half-window from the anchor is "later".
 */
public final class SeqOrder {
    private SeqOrder() {}

    public static final long MOD = 1L << 32;
    public static final long HALF = 1L << 31;

    /** Forward signed distance from a to b modulo 2^32, in (-2^31, 2^31]. */
    public static long forwardDelta(long a, long b) {
        long d = (b - a) & 0xFFFFFFFFL;
        if (d > HALF) d -= MOD;
        return d;
    }

    /** True when b is at or after a on the forward circle anchored at scanStart. */
    public static boolean ge(long b, long a, long anchor) {
        return compare(b, a, anchor) >= 0;
    }

    /**
     * Circular comparison relative to the scan anchor.
     * @return negative/zero/positive as x precedes/equals/follows y.
     */
    public static int compare(long x, long y, long anchor) {
        long dx = forwardDelta(anchor, x);
        long dy = forwardDelta(anchor, y);
        return Long.compare(dx, dy);
    }

    /** Sort key that unwraps sequences onto a monotonic line from the anchor. */
    public static long unwrap(long seq, long anchor) {
        return anchor + forwardDelta(anchor, seq);
    }

    public static long next(long seq) {
        return (seq + 1) & 0xFFFFFFFFL;
    }
}
