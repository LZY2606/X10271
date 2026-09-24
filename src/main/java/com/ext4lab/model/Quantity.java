package com.ext4lab.model;

import com.ext4lab.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A domain quantity. The raw representation is always preserved at import;
 * normalization happens only in the derived layer. Values that cannot be
 * converted stay in place and carry a diagnostic instead of being dropped.
 */
public record Quantity(String raw, String unit, String coordinateBase,
                       Long normalized, String diagnostic) {

    public static Quantity of(String raw, String unit, String coordinateBase) {
        return new Quantity(raw, unit, coordinateBase, null, null);
    }

    public Quantity withNormalized(long v) {
        return new Quantity(raw, unit, coordinateBase, v, null);
    }

    public Quantity withDiagnostic(String d) {
        return new Quantity(raw, unit, coordinateBase, null, d);
    }

    public boolean ok() {
        return diagnostic == null;
    }

    public ObjectNode toJson() {
        ObjectNode n = Json.obj();
        n.put("raw", raw);
        n.put("unit", unit);
        n.put("coordinateBase", coordinateBase);
        if (normalized != null) n.put("normalized", normalized);
        if (diagnostic != null) n.put("diagnostic", diagnostic);
        return n;
    }

    public static Quantity fromJson(com.fasterxml.jackson.databind.JsonNode n) {
        return new Quantity(
                n.get("raw").asText(),
                n.get("unit").asText(),
                n.get("coordinateBase").asText(),
                n.hasNonNull("normalized") ? n.get("normalized").asLong() : null,
                n.hasNonNull("diagnostic") ? n.get("diagnostic").asText() : null);
    }
}
