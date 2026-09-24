package lab.ext4jbd2.domain;

/**
 * JBD2 commit sequence numbers are 32-bit and wrap modulo 2^32.
 * Ordering must use modular comparison (like the kernel's tid_gt/tid_lt),
 * never plain integer sorting. Comparisons are valid within a 2^31 window.
 */
public final class SeqNum {
    private SeqNum() {}

    /** Returns negative if a &lt; b, zero if equal, positive if a &gt; b (mod 2^32). */
    public static int compare(long a, long b) {
        return (int) ((a - b) & 0xFFFFFFFFL);
    }

    public static boolean lt(long a, long b) { return compare(a, b) < 0; }
    public static boolean gt(long a, long b) { return compare(a, b) > 0; }
    public static boolean geq(long a, long b) { return compare(a, b) >= 0; }

    public static long next(long seq) {
        return (seq + 1) & 0xFFFFFFFFL;
    }
}
