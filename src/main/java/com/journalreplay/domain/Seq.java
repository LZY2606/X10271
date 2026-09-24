package com.journalreplay.domain;

/**
 * Wrap-aware 32-bit JBD2 transaction sequence arithmetic.
 *
 * Journal sequences are 32-bit and wrap around the ring; they must never be
 * ordered with plain integer comparison. The kernel rule (include/linux/jbd2.h):
 *   tid_geq(x, y) == ((int)(x - y) >= 0)
 * i.e. x is "not before" y when the signed 32-bit difference is non-negative.
 */
public final class Seq {

    private Seq() {}

    public static long mask(long v) {
        return v & 0xFFFFFFFFL;
    }

    /** Signed 32-bit distance from {@code from} to {@code to} travelling forward. */
    public static int diff(long to, long from) {
        return (int) (mask(to) - mask(from));
    }

    /** true when {@code x} is equal to or after {@code y} in wrap-aware order. */
    public static boolean geq(long x, long y) {
        return diff(x, y) >= 0;
    }

    /** true when {@code x} is strictly after {@code y} in wrap-aware order. */
    public static boolean gt(long x, long y) {
        return diff(x, y) > 0;
    }

    /** true when {@code x} is strictly before {@code y} in wrap-aware order. */
    public static boolean lt(long x, long y) {
        return diff(x, y) < 0;
    }

    public static long next(long seq) {
        return mask(seq + 1);
    }

    /** Order two sequences by forward distance from an anchor (ring order), never plain sort. */
    public static int compareFrom(long anchor, long a, long b) {
        return Integer.compare(diff(a, anchor), diff(b, anchor));
    }
}
