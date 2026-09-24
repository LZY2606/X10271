package com.fslab.jbd2.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * JBD2 sequence numbers are a uint32 ring. They MUST NOT be compared with plain
 * integer ordering across a wrap: a descending jump larger than half the ring
 * marks the wrap boundary, and the low values after it are the newest.
 */
public final class SeqRing {
    public static final long MOD = 0x100000000L;
    public static final long HALF = MOD / 2;

    private SeqRing() {}

    public static boolean isWrapped(List<Long> seqs) {
        if (seqs == null || seqs.size() < 2) return false;
        for (int i = 1; i < seqs.size(); i++) {
            long prev = seqs.get(i - 1);
            long cur = seqs.get(i);
            if (cur < prev && (prev - cur) > HALF) return true;
        }
        return false;
    }

    /**
     * Returns sequences in temporal order. When a wrap is present the pivot is the
     * largest value before the first big descending jump; high values up to the
     * pivot come first, then the wrapped-around low values.
     */
    public static List<Long> order(List<Long> input) {
        List<Long> seqs = new ArrayList<>(input);
        if (!isWrapped(seqs)) {
            seqs.sort(Comparator.naturalOrder());
            return seqs;
        }
        long pivot = seqs.get(0);
        for (int i = 1; i < seqs.size(); i++) {
            long prev = seqs.get(i - 1);
            long cur = seqs.get(i);
            if (cur < prev && (prev - cur) > HALF) break;
            pivot = cur;
        }
        List<Long> low = new ArrayList<>();
        List<Long> high = new ArrayList<>();
        for (long s : seqs) (s <= pivot ? high : low).add(s);
        high.sort(Comparator.naturalOrder());
        low.sort(Comparator.naturalOrder());
        List<Long> out = new ArrayList<>(high.size() + low.size());
        out.addAll(high);
        out.addAll(low);
        return out;
    }

    /** True iff {@code a} precedes {@code b} in temporal ring order, given a known wrap. */
    public static boolean less(long a, long b, boolean wrapped) {
        if (!wrapped) return a < b;
        long d = (b - a) & 0xFFFFFFFFL;
        return d != 0 && d < HALF;
    }
}
