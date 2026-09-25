package com.ext4lab.domain;

/**
 * 32-bit JBD2 sequence arithmetic on a ring. Sequence numbers wrap around, so
 * plain integer sorting is forbidden; all ordering goes through modular distance.
 */
public final class SeqNo {
    public static final long MOD = 1L << 32;
    public static final long HALF = 1L << 31;

    private SeqNo() {}

    public static long norm(long seq) {
        return seq & 0xFFFFFFFFL;
    }

    /** Distance from {@code a} to {@code b} walking forward on the ring, in [0, 2^32). */
    public static long distance(long a, long b) {
        return (norm(b) - norm(a)) & 0xFFFFFFFFL;
    }

    /** True iff {@code b} lies strictly after {@code a} on the sequence ring. */
    public static boolean after(long b, long a) {
        long d = distance(a, b);
        return d != 0 && d < HALF;
    }

    /** Modular comparison: negative if a before b, zero if equal, positive if a after b. */
    public static int compare(long a, long b) {
        long d = distance(a, b);
        if (d == 0) return 0;
        return d < HALF ? 1 : -1;
    }

    public static String hex(long seq) {
        return String.format("0x%08X", norm(seq));
    }
}
