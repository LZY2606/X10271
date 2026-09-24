package fsreplay.core;

/**
 * Wrap-aware 32-bit journal sequence arithmetic.
 * Ring log sequence numbers wrap at 2^32, so plain integer ordering is wrong:
 * 0x00000001 is AFTER 0xFFFFFFFE. We compare via signed modular distance.
 */
public final class Seq {
    private Seq() {}

    /** true iff a is strictly before b in modular sequence space. */
    public static boolean before(long a, long b) {
        return (int) (b - a) > 0;
    }

    /** true iff a is strictly after b. */
    public static boolean after(long a, long b) {
        return before(b, a);
    }

    /** modular distance from a to b (how far b is ahead of a), as signed int. */
    public static int dist(long a, long b) {
        return (int) (b - a);
    }

    public static long u32(long v) {
        return v & 0xFFFFFFFFL;
    }

    public static String hex(long v) {
        return String.format("0x%08X", u32(v));
    }
}
