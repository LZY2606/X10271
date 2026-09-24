package jrnl.domain;

import java.util.List;

/**
 * Filesystem/journal block size. The raw imported representation (with its unit)
 * is preserved verbatim; an unconvertible or illegal value stays at its origin
 * with a diagnostic instead of being dropped or coerced.
 */
public record BlockSize(String rawRepr, Long bytes, String diagnostic) {

    public static final List<Integer> EXT4_VALID = List.of(1024, 2048, 4096);

    public static BlockSize ofBytes(int bytes) {
        return new BlockSize(bytes + " bytes", (long) bytes, validate(bytes));
    }

    public static BlockSize fromRaw(String raw) {
        if (raw == null) {
            return new BlockSize(null, null, "block size representation is absent");
        }
        String s = raw.trim();
        String digits = s.replaceAll("(?i)bytes?", "").trim();
        try {
            long v = Long.parseLong(digits);
            if (v < 0 || v > 0x7fffffffL) {
                return new BlockSize(raw, null, "block size out of range: " + raw);
            }
            return new BlockSize(raw, v, validate((int) v));
        } catch (NumberFormatException e) {
            return new BlockSize(raw, null, "unconvertible block size representation kept at origin: " + raw);
        }
    }

    private static String validate(int bytes) {
        return EXT4_VALID.contains(bytes)
                ? null
                : "invalid ext4/JBD2 block size: " + bytes + " bytes (allowed 1024/2048/4096)";
    }

    public boolean valid() {
        return bytes != null && diagnostic == null;
    }
}
