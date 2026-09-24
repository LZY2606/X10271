package fsreplay.domain;

/**
 * A domain quantity that keeps its raw imported representation plus unit /
 * coordinate basis. Normalization happens only in the derived layer; values
 * that cannot be converted stay in place with a diagnostic and are never
 * dropped for the sake of drawing a chart.
 */
public final class Quantity {
    public final String raw;
    public final String unit;
    public final String basis;
    public final Long normalized;
    public final String diagnostic;

    private Quantity(String raw, String unit, String basis, Long normalized, String diagnostic) {
        this.raw = raw;
        this.unit = unit;
        this.basis = basis;
        this.normalized = normalized;
        this.diagnostic = diagnostic;
    }

    public static Quantity of(String raw, String unit, String basis) {
        try {
            return new Quantity(raw, unit, basis, parse(raw), null);
        } catch (RuntimeException e) {
            return new Quantity(raw, unit, basis, null,
                    "cannot normalize '" + raw + "' (" + unit + ", basis " + basis + "): " + e.getMessage());
        }
    }

    public static Quantity invalid(String raw, String unit, String basis, String diagnostic) {
        return new Quantity(raw, unit, basis, null, diagnostic);
    }

    public boolean ok() { return normalized != null; }

    private static long parse(String raw) {
        String s = raw.trim();
        int sp = s.indexOf(' ');
        String num = sp >= 0 ? s.substring(0, sp) : s;
        if (num.startsWith("0x") || num.startsWith("0X")) return Long.parseUnsignedLong(num.substring(2), 16);
        return Long.parseLong(num);
    }

    @Override public String toString() {
        return raw + " [" + unit + "|" + basis + "]" + (ok() ? " -> " + normalized : " !! " + diagnostic);
    }
}
