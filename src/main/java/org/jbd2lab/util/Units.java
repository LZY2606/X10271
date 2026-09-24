package org.jbd2lab.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Values with coordinates / units. Domain quantities never travel as bare numbers.
 */
public final class Units {
    private Units() {}

    public static final String U_BLOCK = "block";
    public static final String U_JRNL_BLOCK = "journal-block";
    public static final String U_FS_BLOCK = "fs-block";
    public static final String U_BYTE = "byte";
    public static final String U_SEQ32 = "u32-circular";
    public static final String U_NONE = "none";

    public record Coord(String unit, long index) {
        @Override public String toString() { return unit + "#" + index; }
        public static Coord jblk(long index) { return new Coord(U_JRNL_BLOCK, index); }
        public static Coord fsblk(long index) { return new Coord(U_FS_BLOCK, index); }
    }

 /**
     * A value imported from raw material: original representation is retained; normalization into
     * a long may fail, in which case {@link #normalized()} is null and a diagnostic is attached.
     */
    public record RawLong(String original, Long normalized, String diagnostic) {
        public static RawLong of(String original, long value) {
            return new RawLong(original, value, null);
        }
        public static RawLong unconvertible(String original, String diagnostic) {
            return new RawLong(original, null, diagnostic);
        }
        public boolean valid() { return normalized != null; }
        public long require() {
            if (normalized == null) {
                throw new IllegalStateException("unconvertible value: " + original + " (" + diagnostic + ")");
            }
            return normalized;
        }
    }

    public static RawLong parsePositivePowerOfTwo(String original, String what) {
        try {
            long v = Long.parseLong(original.trim().startsWith("0x")
                    ? Long.parseUnsignedLong(original.trim().substring(2), 16)
                    : Long.parseLong(original.trim()));
            if (v < 1024 || v > 65536 || Long.bitCount(v) != 1) {
                return RawLong.unconvertible(original,
                        what + " must be a power of two in [1024,65536], got " + v);
            }
            return RawLong.of(original, v);
        } catch (NumberFormatException e) {
            return RawLong.unconvertible(original, what + " is not an integer: " + e.getMessage());
        }
    }

    public static String[] diagnostics(List<String> messages) {
        List<String> copy = new ArrayList<>(messages);
        return copy.toArray(new String[0]);
    }
}
