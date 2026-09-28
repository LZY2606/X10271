package org.fslab.jbd2.model;

/**
 * A domain quantity that keeps the original textual representation and its
 * coordinate/unit basis. Normalization happens only in the derivation layer;
 * when conversion is impossible {@code value} stays null and a diagnostic is
 * attached at the source position.
 */
public record Q(String originalText, Long value, String unit, String basis, String diagnostic) {

    public static Q of(String text, long value, String unit, String basis) {
        return new Q(text, value, unit, basis, null);
    }

    public static Q unconvertible(String text, String unit, String basis, String diagnostic) {
        return new Q(text, null, unit, basis, diagnostic);
    }

    public boolean convertible() {
        return value != null;
    }
}
