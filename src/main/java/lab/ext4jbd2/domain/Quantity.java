package lab.ext4jbd2.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A domain quantity. The raw representation from import is always preserved;
 * normalization happens only in the derived layer. Values that cannot be
 * converted stay in place with a diagnostic instead of being dropped.
 */
public final class Quantity {
    public final String raw;
    public final String unit;     // e.g. "B", "KiB", "block", "seq"
    public final String basis;    // coordinate basis, e.g. "journal-block-index", "fs-block-index", "jbd2-seq-mod-2^32"
    public final Long normalized; // canonical value in `unit` (bytes for sizes), null if not convertible
    public final String diagnostic;

    public Quantity(String raw, String unit, String basis, Long normalized, String diagnostic) {
        this.raw = raw;
        this.unit = unit;
        this.basis = basis;
        this.normalized = normalized;
        this.diagnostic = diagnostic;
    }

    public static Quantity of(long value, String unit, String basis) {
        return new Quantity(Long.toString(value), unit, basis, value, null);
    }

    public static Quantity raw(String raw, String unit, String basis) {
        return new Quantity(raw, unit, basis, null, null);
    }

    /** Derived-layer normalization. Never throws; unconvertible values get a diagnostic. */
    public Quantity normalize() {
        if (normalized != null) return this;
        String r = raw == null ? "" : raw.trim();
        try {
            if (r.matches("[0-9]+")) {
                return new Quantity(raw, unit, basis, Long.parseLong(r), null);
            }
            if (r.matches("0x[0-9a-fA-F]+")) {
                return new Quantity(raw, unit, basis, Long.parseLong(r.substring(2), 16), null);
            }
            if (r.matches("[0-9]+\\s*[Kk]i[Bb]")) {
                long kib = Long.parseLong(r.replaceAll("[^0-9]", ""));
                return new Quantity(raw, "B", basis, kib * 1024, "rewritten from KiB to B");
            }
            if (r.matches("[0-9]+\\s*[Kk][Bb]?")) {
                long kb = Long.parseLong(r.replaceAll("[^0-9]", ""));
                return new Quantity(raw, "B", basis, kb * 1024, "rewritten from KB to B");
            }
        } catch (RuntimeException e) {
            return new Quantity(raw, unit, basis, null, "cannot normalize: " + e.getMessage());
        }
        return new Quantity(raw, unit, basis, null,
                "cannot normalize quantity '" + raw + "' with unit '" + unit + "'; left in place");
    }

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("raw", raw);
        n.put("unit", unit);
        if (basis != null) n.put("basis", basis);
        if (normalized != null) n.put("normalized", normalized);
        if (diagnostic != null) n.put("diagnostic", diagnostic);
        return n;
    }

    public static Quantity fromJson(JsonNode n) {
        return new Quantity(
                n.path("raw").isNull() ? null : n.path("raw").asText(),
                n.path("unit").isNull() ? null : n.path("unit").asText(),
                n.path("basis").isNull() ? null : n.path("basis").asText(),
                n.hasNonNull("normalized") ? n.path("normalized").asLong() : null,
                n.hasNonNull("diagnostic") ? n.path("diagnostic").asText() : null);
    }
}
