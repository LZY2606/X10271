package lab.ext4.jbd2;

/**
 * Wraparound-aware comparison of JBD2 32-bit transaction sequence numbers.
 * Ring sequence numbers must never be ordered with plain integer sorting:
 * two sequences are ordered by their signed modular distance in a 2^32 ring.
 */
public final class Seq {
    private Seq() {}

    /** Signed modular distance from {@code from} to {@code to}; positive means {@code to} is newer. */
    public static int distance(long from, long to) {
        return (int) (((to - from) & 0xFFFF_FFFFL));
    }

    /** True when {@code a} is strictly newer than {@code b} on the sequence ring. */
    public static boolean after(long a, long b) {
        return distance(b, a) > 0;
    }

    /** Three-way modular comparison; only meaningful for sequences within 2^31 of each other. */
    public static int compare(long a, long b) {
        return Integer.signum(distance(b, a));
    }

    public static String hex(long seq) {
        return String.format("0x%08X", seq & 0xFFFF_FFFFL);
    }
}
