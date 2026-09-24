package jrnl.domain;

import java.util.Arrays;

/**
 * JBD2 checksum version. The imported label is preserved in {@code rawRepr};
 * only the derived layer normalises it to an algorithm.
 */
public enum ChecksumVersion {
    NONE("none", "JBD2_CSUM_NONE"),
    V2_CRC32("crc32", "JBD2_CSUM_V2(crc32)"),
    V3_CRC32C("crc32c", "JBD2_CSUM_V3(crc32c)");

    private final String algo;
    private final String canonicalLabel;

    ChecksumVersion(String algo, String canonicalLabel) {
        this.algo = algo;
        this.canonicalLabel = canonicalLabel;
    }

    public String algo() {
        return algo;
    }

    public String canonicalLabel() {
        return canonicalLabel;
    }

    public static ChecksumVersion fromRaw(String raw) {
        if (raw == null) {
            return NONE;
        }
        String s = raw.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(v -> s.equals(v.algo) || s.equalsIgnoreCase(v.name())
                        || s.toLowerCase().contains(v.algo))
                .findFirst()
                .orElse(NONE);
    }
}
