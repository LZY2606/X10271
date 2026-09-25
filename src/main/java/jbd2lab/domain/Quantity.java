package jbd2lab.domain;

import java.util.Objects;

/**
 * A domain quantity carrying its unit / coordinate basis and its original textual
 * representation. Normalisation to a canonical numeric value happens only in the
 * derivation layer; an unconvertible input stays on the object with a diagnostic
 * and {@link #normalized()} == null, so it is never silently dropped for a drawing.
 */
public final class Quantity {
    private final String raw;
    private final String unit;
    private final Long normalized;
    private final String diagnostic;

    private Quantity(String raw, String unit, Long normalized, String diagnostic) {
        this.raw = raw;
        this.unit = unit;
        this.normalized = normalized;
        this.diagnostic = diagnostic;
    }

    public static Quantity of(String raw, String unit, long normalized) {
        return new Quantity(raw, unit, normalized, null);
    }

    public static Quantity invalid(String raw, String unit, String diagnostic) {
        return new Quantity(raw, unit, null, diagnostic);
    }

    public String raw() {
        return raw;
    }

    public String unit() {
        return unit;
    }

    public Long normalized() {
        return normalized;
    }

    public boolean valid() {
        return normalized != null;
    }

    public String diagnostic() {
        return diagnostic;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Quantity q && Objects.equals(raw, q.raw) && Objects.equals(unit, q.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, unit);
    }

    @Override
    public String toString() {
        return raw + " " + unit + (normalized != null ? " (=" + normalized + ")" : " [INVALID: " + diagnostic + "]");
    }
}
