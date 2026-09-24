package com.ext4lab.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.TreeMap;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private Json() {}

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode read(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("bad json: " + e.getMessage(), e);
        }
    }

    /** Canonical serialization (sorted keys) used for content hashing. */
    public static String canonical(Object o) {
        try {
            JsonNode n = MAPPER.valueToTree(o);
            return MAPPER.writeValueAsString(sort(n));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sort(JsonNode n) {
        if (n instanceof ObjectNode on) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            on.fields().forEachRemaining(e -> sorted.put(e.getKey(), sort(e.getValue())));
            ObjectNode out = MAPPER.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (n.isArray()) {
            var arr = MAPPER.createArrayNode();
            n.forEach(c -> arr.add(sort(c)));
            return arr;
        }
        return n;
    }
}
