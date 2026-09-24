package lab.jbd2.model;

/**
 * An imported domain value that always carries its unit / coordinate basis and its
 * original textual representation. Unconvertible values stay at the origin with a
 * diagnostic instead of being silently dropped.
 */
public record Quantity(String raw, Long value, String unit, String basis, String diagnostic) {

    public static Quantity of(long value, String unit, String basis) {
        return new Quantity(Long.toString(value), value, unit, basis, null);
    }

    public static Quantity raw(String raw, String unit, String basis, String diagnostic) {
        return new Quantity(raw, null, unit, basis, diagnostic);
    }

    public boolean convertible() {
        return value != null && diagnostic == null;
    }
}
