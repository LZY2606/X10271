package jbd2lab.core;

import java.util.Objects;

/**
 * A scalar that always carries its physical unit or coordinate basis. Import keeps the
 * original textual representation ({@code rawText}); the derived layer performs
 * normalization. Values that cannot be converted stay at their origin with a diagnostic
 * ({@code invalid=true}) instead of being dropped to make a graph render.
 */
public final class Quant {
    public static final String UNIT_BYTES = "bytes";
    public static final String UNIT_FS_BLOCKS = "fs_blocks";
    public static final String UNIT_JRNL_BLOCKS = "journal_blocks";
    public static final String UNIT_SEQ = "jbd2_sequence_u32";
    public static final String UNIT_ENUM = "enum";

    private final long value;
    private final String unit;
    private final String rawText;
    private final boolean invalid;
    private final String diagnostic;

    private Quant(long value, String unit, String rawText, boolean invalid, String diagnostic) {
        this.value = value;
        this.unit = Objects.requireNonNull(unit);
        this.rawText = rawText;
        this.invalid = invalid;
        this.diagnostic = diagnostic;
    }

    public static Quant of(long value, String unit, String rawText) {
        return new Quant(value, unit, rawText, false, null);
    }

    public static Quant of(long value, String unit) {
        return of(value, unit, Long.toString(value));
    }

    public static Quant invalid(String rawText, String diagnostic, String unit) {
        return new Quant(0L, unit, rawText, true, diagnostic);
    }

    public long value() { return value; }
    public String unit() { return unit; }
    public String rawText() { return rawText; }
    public boolean invalid() { return invalid; }
    public String diagnostic() { return diagnostic; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Quant)) return false;
        Quant q = (Quant) o;
        return value == q.value && unit.equals(q.unit) && invalid == q.invalid
                && Objects.equals(rawText, q.rawText) && Objects.equals(diagnostic, q.diagnostic);
    }
    @Override public int hashCode() { return Objects.hash(value, unit, rawText, invalid, diagnostic); }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"value\":").append(value);
        sb.append(",\"unit\":\"").append(unit).append('"');
        sb.append(",\"raw\":\"").append(escape(rawText)).append('"');
        sb.append(",\"invalid\":").append(invalid);
        if (diagnostic != null) sb.append(",\"diagnostic\":\"").append(escape(diagnostic)).append('"');
        return sb.append('}').toString();
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
