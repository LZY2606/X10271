package org.jbd2lab.scan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine-readable judgment basis. Every decision the engine makes is emitted as an event with
 * a stable code, a certainty level and structured attributes (coordinates carry their units).
 */
public record LabEvent(String code, String severity, String certainty, String message,
                       Map<String, Object> attrs, java.util.List<String> evidence) {
    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String ERROR = "error";

    public static final String CERTAIN = "certain";
    public static final String UNCERTAIN = "uncertain";

    public static Builder code(String code) {
        return new Builder(code);
    }

    public static final class Builder {
        private final String code;
        private String severity = INFO;
        private String certainty = CERTAIN;
        private String message = "";
        private final Map<String, Object> attrs = new LinkedHashMap<>();
        private final java.util.List<String> evidence = new java.util.ArrayList<>();

        Builder(String code) { this.code = code; }
        public Builder severity(String s) { this.severity = s; return this; }
        public Builder uncertain() { this.certainty = UNCERTAIN; return this; }
        public Builder message(String m) { this.message = m; return this; }
        public Builder attr(String k, Object v) { attrs.put(k, v); return this; }
        public Builder coord(String k, String unit, long index) {
            attrs.put(k, Map.of("unit", unit, "index", index)); return this;
        }
        public Builder evidence(String e) { evidence.add(e); return this; }
        public LabEvent build() {
            return new LabEvent(code, severity, certainty, message,
                    Map.copyOf(attrs), List.copyOf(evidence));
        }
    }
}
