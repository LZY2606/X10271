package lab.ext4.journal;

/**
 * JBD2 32-bit sequence numbers wrap around the ring. Comparisons MUST use
 * modular (serial number) arithmetic, never plain integer sorting.
 */
public final class Seq {
    private Seq() {}

    /** Modular compare: negative if a is "before" b within half the 32-bit space. */
    public static int compare(long a, long b) {
        return Long.compare(distance(b, a), 0);
    }

    /** Forward distance from `from` to `to` in modular space, as signed 32-bit. */
    public static int distance(long from, long to) {
        return (int) ((to - from) & 0xFFFFFFFFL);
    }

    public static boolean before(long a, long b) {
        return distance(a, b) > 0;
    }

    public static boolean afterOrEqual(long a, long b) {
        return distance(b, a) >= 0;
    }

    /** True if revoke recorded at revokeSeq applies to a transaction with txSeq. */
    public static boolean revokeCovers(long revokeSeq, long txSeq) {
        return afterOrEqual(revokeSeq, txSeq);
    }

    public static String hex(long seq) {
        return String.format("0x%08X", seq & 0xFFFFFFFFL);
    }
}
