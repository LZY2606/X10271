package org.fsreplay.jbd2lab.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * An imported or derived numeric quantity that keeps:
 *  - raw: the exact representation as supplied by the importer (never normalized away),
 *  - value: the normalized numeric value when conversion was possible,
 *  - unit: the dimension and its canonical symbol,
 *  - basis: the coordinate reference the value is expressed against.
 *
 * An unconvertible value stays in {@code raw} with {@code value == null} and a
 * diagnostic attached by the caller; it is never dropped for plotting.
 */
@JsonPropertyOrder({"raw", "value", "unit", "basis", "convertible"})
public final class UnitValue {
    private final String raw;
    private final Double value;
    private final Unit unit;
    private final String basis;

    public UnitValue(@JsonProperty("raw") String raw,
                     @JsonProperty("value") Double value,
                     @JsonProperty("unit") Unit unit,
                     @JsonProperty("basis") String basis) {
        this.raw = raw;
        this.value = value;
        this.unit = unit;
        this.basis = basis;
    }

    public static UnitValue of(long value, Unit unit, String basis) {
        return new UnitValue(Long.toString(value), (double) value, unit, basis);
    }

    public static UnitValue ofRaw(String raw, Unit unit, String basis) {
        return new UnitValue(raw, null, unit, basis);
    }

    public String raw() {
        return raw;
    }

    public Double value() {
        return value;
    }

    public boolean convertible() {
        return value != null;
    }

    public long longValue() {
        if (value == null) {
            throw new IllegalStateException("unconvertible UnitValue: " + raw);
        }
        return value.longValue();
    }

    public Unit unit() {
        return unit;
    }

    public String basis() {
        return basis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UnitValue that)) return false;
        return Objects.equals(raw, that.raw) && Objects.equals(value, that.value)
                && unit == that.unit && Objects.equals(basis, that.basis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, value, unit, basis);
    }

    @Override
    public String toString() {
        return raw + " " + unit.symbol() + (basis != null && !basis.isBlank() ? " (" + basis + ")" : "");
    }
}
