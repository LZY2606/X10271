package com.ext4lab.jbd2;

/**
 * Wrap-aware 32-bit journal sequence arithmetic.
 * Ring order never uses plain integer sorting: comparison is only valid
 * inside a half-range window (2^31) around a reference point.
 */
public final class Seq {
    private Seq() {}

    /** Returns negative/zero/positive as a-b in wrap-aware half-range semantics. */
    public static int compare(long a, long b) {
        long d = (a - b) & 0xFFFF_FFFFL;
        if (d == 0) return 0;
        return d < 0x8000_0000L ? 1 : -1;
    }

    public static long add(long seq, long delta) {
        return (seq + delta) & 0xFFFF_FFFFL;
    }

    public static String fmt(long seq) {
        return String.format("0x%08X", seq & 0xFFFF_FFFFL);
    }
}
