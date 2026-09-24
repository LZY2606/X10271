package fsreplay.domain;

/**
 * Wrap-aware 32-bit journal sequence comparison.
 * Ring log sequences must never be ordered with plain integer sort:
 * 0x00000001 follows 0xFFFFFFFF after wraparound.
 */
public final class Seq {
    private Seq() {}

    public static long norm(long s) { return s & 0xFFFFFFFFL; }

    /** @return negative if a is older than b, 0 if equal, positive if a is newer. */
    public static int compare(long a, long b) {
        long ai = norm(a), bi = norm(b);
        if (ai == bi) return 0;
        long diff = (ai - bi) & 0xFFFFFFFFL;
        return diff < 0x80000000L ? 1 : -1;
    }

    /** true when a is not newer than b (a <= b in ring order). */
    public static boolean beforeOrEqual(long a, long b) { return compare(a, b) <= 0; }

    public static String hex(long s) { return String.format("0x%08X", norm(s)); }
}
