package lab.jbd2.core;

/**
 * JBD2 sequence numbers are 32-bit and wrap around the journal ring.
 * Ordering must use modular (half-range) comparison, never plain integer sort.
 */
public final class Seq {
    public static final long MOD = 1L << 32;
    public static final long HALF = 1L << 31;

    private Seq() {}

    public static long norm(long s) {
        return s & 0xFFFF_FFFFL;
    }

    /** @return positive if a is after b, negative if before, 0 if equal (mod 2^32). */
    public static int compare(long a, long b) {
        long d = (norm(a) - norm(b)) & 0xFFFF_FFFFL;
        if (d == 0) return 0;
        return d < HALF ? 1 : -1;
    }

    public static boolean after(long a, long b) {
        return compare(a, b) > 0;
    }

    public static boolean afterOrEq(long a, long b) {
        return compare(a, b) >= 0;
    }

    public static long next(long s) {
        return norm(s + 1);
    }

    public static String hex(long s) {
        return String.format("0x%08x", norm(s));
    }
}
