package lab.jbd2.core;

/**
 * Wrap-aware ordering for 32-bit JBD2 journal sequence ids. Sequence numbers increment
 * monotonically and wrap at 2^32, so a plain integer sort puts a wrapped sequence
 * (e.g. 2) far after a pre-wrap sequence (e.g. 0xFFFFFFFE), which is wrong. The methods
 * here unwrap a stream observed in ring physical order and compare unwrapped values.
 */
public final class SequenceRing {

    public static final long MOD = 1L << 32;

    private SequenceRing() {
    }

    /**
     * Unwraps the next raw sequence assuming the stream is physically monotonic: a value
     * that drops by more than half the ring is interpreted as one wrap later.
     */
    public static long unwrapNext(long previousUnwrapped, long raw) {
        long base = previousUnwrapped & ~(MOD - 1);
        long candidate = base + raw;
        if (candidate + MOD / 2 < previousUnwrapped) {
            candidate += MOD;
        } else if (candidate - previousUnwrapped > MOD / 2) {
            candidate -= MOD;
        }
        return candidate;
    }

    /** Unwraps a whole physically-ordered stream of raw sequence ids. */
    public static long[] unwrapStream(long[] raw) {
        long[] out = new long[raw.length];
        if (raw.length == 0) {
            return out;
        }
        out[0] = raw[0];
        for (int i = 1; i < raw.length; i++) {
            out[i] = unwrapNext(out[i - 1], raw[i]);
        }
        return out;
    }

    /**
     * Forward distance {@code from -> to} on the ring, in [0, MOD). A small positive
     * result means {@code to} comes after {@code from} even when it numerically wrapped.
     */
    public static long forwardDistance(long from, long to) {
        return (to - from) & (MOD - 1);
    }

    /** True if {@code seq} is strictly after {@code anchor} in ring order. */
    public static boolean after(long seq, long anchor) {
        long d = forwardDistance(anchor, seq);
        return d != 0 && d < MOD / 2;
    }
}
