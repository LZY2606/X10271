package lab;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/** JBD2 commit/descriptor checksum helper (crc32c, as used by ext4 journal v2/v3). */
public final class Crc32c {
    private Crc32c() {}

    public static String hex(String canonicalContent) {
        CRC32C crc = new CRC32C();
        crc.update(canonicalContent.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    public static boolean matches(String canonicalContent, String storedHex) {
        if (storedHex == null) return false;
        return hex(canonicalContent).equalsIgnoreCase(storedHex.trim());
    }
}
