package lab.jbd2.core;

/**
 * A domain quantity keeps its raw imported representation plus a unit/coordinate
 * basis. Normalization happens only in the derived layer; values that cannot be
 * converted stay in place with a diagnostic instead of being dropped.
 */
public record Quantity(String raw, String unit, Long normalized, String diagnostic) {

    public static Quantity of(long value, String unit) {
        return new Quantity(Long.toString(value), unit, value, null);
    }

    public static Quantity raw(String raw, String unit) {
        try {
            return new Quantity(raw, unit, Long.parseLong(raw), null);
        } catch (NumberFormatException e) {
            return new Quantity(raw, unit, null, "unparseable numeric value: " + raw);
        }
    }

    public static Quantity invalid(String raw, String unit, String diagnostic) {
        return new Quantity(raw, unit, null, diagnostic);
    }

    public boolean convertible() {
        return normalized != null;
    }

    public String display() {
        String base = raw + (unit == null || unit.isEmpty() ? "" : " " + unit);
        return diagnostic == null ? base : base + " [diagnostic: " + diagnostic + "]";
    }
}
