package com.ext4lab.jbd2;

/**
 * A domain quantity keeps its raw representation plus unit and coordinate
 * basis. Import preserves raw; only the derived layer normalizes. Values that
 * cannot be converted stay raw and carry a diagnostic.
 */
public final class Quantity {
    public final String raw;      // original representation, never rewritten
    public final Long value;      // normalized value, null when unconvertible
    public final String unit;     // "block" | "byte" | "ring-slot" | "seq" | "count"
    public final String basis;    // coordinate basis, e.g. "fs-block:4096" or "journal-offset:0"
    public final String diagnostic; // non-null when conversion failed

    private Quantity(String raw, Long value, String unit, String basis, String diagnostic) {
        this.raw = raw;
        this.value = value;
        this.unit = unit;
        this.basis = basis;
        this.diagnostic = diagnostic;
    }

    public static Quantity of(long value, String unit, String basis) {
        return new Quantity(Long.toString(value), value, unit, basis, null);
    }

    public static Quantity raw(String raw, String unit, String basis) {
        Long v = null;
        try {
            v = Long.decode(raw.trim());
        } catch (Exception ignored) {}
        return new Quantity(raw, v, unit, basis,
                v == null ? "unconvertible: kept raw at original position" : null);
    }

    public static Quantity invalid(String raw, String unit, String basis, String diagnostic) {
        return new Quantity(raw, null, unit, basis, diagnostic);
    }

    public String display() {
        return raw + " [" + unit + " @" + basis + "]" + (diagnostic != null ? " !" + diagnostic : "");
    }
}
