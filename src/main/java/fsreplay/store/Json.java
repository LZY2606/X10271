package fsreplay.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON (objects, arrays, strings, numbers, bools, null) — no external dependency. */
public final class Json {
    private Json() {}

    public static String esc(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String obj(Map<String, ?> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(esc(e.getKey())).append(':').append(val(e.getValue()));
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    public static String val(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return esc(s);
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map<?, ?> m) return obj((Map<String, ?>) m);
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(val(l.get(i)));
            }
            return sb.append(']').toString();
        }
        return esc(v.toString());
    }

    public static Object parse(String s) {
        return new P(s).value();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObj(String s) {
        return (Map<String, Object>) parse(s);
    }

    private static final class P {
        private final String s;
        private int i;

        P(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws(); i++; // ':'
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                i++; // '}'
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                i++; // ']'
                return l;
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    sb.append(switch (e) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> e;
                    });
                } else sb.append(c);
            }
        }

        private Number number() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
            return Long.parseLong(t);
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
