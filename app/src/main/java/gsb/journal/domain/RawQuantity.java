package gsb.journal.domain;

import java.util.Objects;

/**
 * A value exactly as it appeared in the import, alongside its normalised form.
 * If the raw representation cannot be converted, {@link #valid()} is false, the
 * normalised value stays null and a diagnostic is kept in place. The derived
 * layer is forbidden from inventing a value purely to make a diagram render.
 */
public final class RawQuantity {
    private final String raw;
    private final Long normalised;
    private final Unit unit;
    private final String coordinate;
    private final String diagnostic;

    private RawQuantity(String raw, Long normalised, Unit unit, String coordinate, String diagnostic) {
        this.raw = raw;
        this.normalised = normalised;
        this.unit = unit;
        this.coordinate = coordinate;
        this.diagnostic = diagnostic;
    }

    public static RawQuantity of(long value, Unit unit, String coordinate) {
        return new RawQuantity(Long.toString(value), value, unit, coordinate, null);
    }

    public static RawQuantity ofRaw(String raw, long value, Unit unit, String coordinate) {
        return new RawQuantity(raw, value, unit, coordinate, null);
    }

    public static RawQuantity invalid(String raw, Unit unit, String coordinate, String diagnostic) {
        return new RawQuantity(raw, null, unit == null ? Unit.NONE : unit, coordinate, diagnostic);
    }

    public boolean valid() {
        return normalised != null;
    }

    public String raw() {
        return raw;
    }

    public long value() {
        if (normalised == null) {
            throw new IllegalStateException("quantity at " + coordinate + " has no normalised value: " + diagnostic);
        }
        return normalised;
    }

    public Long valueOrNull() {
        return normalised;
    }

    public Unit unit() {
        return unit;
    }

    public String coordinate() {
        return coordinate;
    }

    public String diagnostic() {
        return diagnostic;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RawQuantity that)) {
            return false;
        }
        return Objects.equals(raw, that.raw) && Objects.equals(normalised, that.normalised)
                && unit == that.unit && Objects.equals(coordinate, that.coordinate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, normalised, unit, coordinate);
    }

    @Override
    public String toString() {
        return valid() ? normalised + " " + unit.shortName() : raw + " (unconvertible: " + diagnostic + ")";
    }
}
