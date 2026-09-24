package com.fslab.jbd2.model;

/**
 * A domain value that keeps the raw on-disk representation and its coordinate basis.
 * Normalization happens only in the derivation layer; values that cannot be converted
 * stay in place with value=null and a diagnostic instead of being dropped.
 */
public record Quantity(
        String rawHex,
        Long value,
        String unit,
        String coordinateBasis,
        boolean convertible,
        String diagnostic) {

    public static Quantity raw(byte[] bytes, Long value, String unit, String basis) {
        return new Quantity(Hex.encode(bytes), value, unit, basis, value != null, null);
    }

    public static Quantity unconvertible(byte[] bytes, String unit, String basis, String why) {
        return new Quantity(Hex.encode(bytes), null, unit, basis, false, why);
    }
}
