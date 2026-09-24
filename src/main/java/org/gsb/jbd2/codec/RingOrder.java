package org.gsb.jbd2.codec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RingOrder {
    private static final long SPACE = 1L << 32;

    private RingOrder() {}

    public static long forwardDistance(long from, long to) {
        return (to - from) & (SPACE - 1);
    }

    public static boolean isAfter(long candidate, long reference) {
        long distance = forwardDistance(reference, candidate);
        return distance != 0 && distance < SPACE / 2;
    }

    public static long latestSequence(List<Long> sequences) {
        if (sequences.isEmpty()) throw new IllegalArgumentException("empty sequence ring");
        long anchor = sequences.get(0);
        return sequences.stream()
                .max(Comparator.comparingLong(sequence -> forwardDistance(anchor, sequence)))
                .orElseThrow();
    }

    public static List<Long> physicalOrder(long start, int ringLength, Long endInclusive) {
        List<Long> result = new ArrayList<>();
        if (ringLength <= 0) return result;
        long end = endInclusive == null ? start + ringLength - 1 : endInclusive;
        long count = (end - start + 1);
        if (count < 1) count = ringLength;
        for (long step = 0; step < count; step++) {
            result.add((start + step) % ringLength);
        }
        return result;
    }

    public static boolean rangeContains(long rangeStart, long rangeEnd, long value) {
        long start = rangeStart & (SPACE - 1);
        long end = rangeEnd & (SPACE - 1);
        long candidate = value & (SPACE - 1);
        if (start <= end) return candidate >= start && candidate <= end;
        return candidate >= start || candidate <= end;
    }
}
