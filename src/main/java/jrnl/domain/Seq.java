package jrnl.domain;

import java.util.Set;

/**
 * 32-bit unsigned JBD2 transaction sequence number.
 * Sequence numbers are coordinates on a ring (mod 2^32); they must never be
 * ordered with plain integer comparison. 0xFFFFFFFF is immediately followed by
 * 0x00000000. All arithmetic is wraparound-preserving.
 */
public record Seq(long value) implements Comparable<Seq> {

    public static final long MOD = 1L << 32;
    public static final long MASK = MOD - 1;
    public static final long HALF = 1L << 31;

    public Seq {
        if (value < 0 || value > MASK) {
            throw new IllegalArgumentException("sequence out of uint32 range: " + value);
        }
    }

    public static Seq of(long v) {
        return new Seq(v & MASK);
    }

    public static Seq parse(String raw) {
        String s = raw.trim();
        int radix = 10;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
            radix = 16;
        }
        return new Seq(Long.parseUnsignedLong(s, radix) & MASK);
    }

    public Seq plus(long delta) {
        return new Seq((value + delta) & MASK);
    }

    /** Signed forward distance on the ring, in (-2^31, 2^31]. Positive means other is after this. */
    public long signedDelta(Seq other) {
        long d = (other.value - value) & MASK;
        return d < HALF ? d : d - MOD;
    }

    public boolean equalTo(Seq other) {
        return value == other.value;
    }

    /** true iff {@code other} is strictly newer (after) this sequence on the ring. */
    public boolean before(Seq other) {
        long d = signedDelta(other);
        return d > 0;
    }

    /**
     * Revoke range rule: a revoke recorded at sequence {@code revokeSeq} masks
     * writes whose sequence is in the open range (... older than revokeSeq).
     * Equal sequence is NOT masked (intra-transaction order wins).
     */
    public boolean olderThan(Seq revokeSeq) {
        return before(revokeSeq);
    }

    /** Is this sequence within {@code [from, to]} walking forward on the ring? */
    public boolean inForwardRange(Seq from, Seq to) {
        long a = from.signedDelta(this);
        long b = from.signedDelta(to);
        return a >= 0 && a <= b;
    }

    /** Stable ring order, anchored at the given oldest sequence. */
    public static java.util.Comparator<Seq> ringOrder(Seq anchor) {
        return (x, y) -> Long.compare(anchor.signedDelta(x), anchor.signedDelta(y));
    }

    public static Seq newestOf(Set<Seq> seqs, Seq anchor) {
        return seqs.stream().max(ringOrder(anchor)).orElseThrow();
    }

    public String hex() {
        return String.format("0x%08x", value);
    }

    @Override
    public int compareTo(Seq o) {
        throw new UnsupportedOperationException(
                "Seq has no global integer order; use before()/ringOrder(anchor)");
    }

    @Override
    public String toString() {
        return hex();
    }
}
