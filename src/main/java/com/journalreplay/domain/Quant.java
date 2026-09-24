package com.journalreplay.domain;

/**
 * A domain quantity that keeps its raw imported representation together with
 * its unit / coordinate basis. Normalisation happens only in the derived
 * layer; values that cannot be converted stay in place with a diagnostic and
 * are never dropped for the sake of drawing a chart.
 */
public record Quant(String raw, String unit, String basis, Long normalized, String diagnostic) {

    public static Quant of(String raw, String unit, String basis) {
        Long v = parse(raw);
        if (v == null) {
            return new Quant(raw, unit, basis, null,
                    "unconvertible value kept in place: '" + raw + "' (unit=" + unit + ")");
        }
        return new Quant(raw, unit, basis, v, null);
    }

    public static Quant ofLong(long v, String unit, String basis) {
        return new Quant(Long.toString(v), unit, basis, v, null);
    }

    public boolean ok() {
        return normalized != null && diagnostic == null;
    }

    /** Parse decimal, 0x-hex, and K/M/G (1024-based) suffixes. Anything else is left raw. */
    static Long parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        long mul = 1;
        if (s.length() > 1) {
            char last = Character.toUpperCase(s.charAt(s.length() - 1));
            if (last == 'K') { mul = 1024L; s = s.substring(0, s.length() - 1); }
            else if (last == 'M') { mul = 1024L * 1024; s = s.substring(0, s.length() - 1); }
            else if (last == 'G') { mul = 1024L * 1024 * 1024; s = s.substring(0, s.length() - 1); }
        }
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16) * mul;
            }
            return Long.parseLong(s) * mul;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
