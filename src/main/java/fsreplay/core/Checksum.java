package fsreplay.core;

import java.util.zip.CRC32;
import java.util.zip.CRC32C;

/** Journal checksum variants. v2 = CRC32 (legacy journal_checksum), v3 = CRC32C (csum v3). */
public enum Checksum {
    V2("crc32"), V3("crc32c");

    public final String label;

    Checksum(String label) { this.label = label; }

    public static Checksum parse(String s) {
        if (s == null) return null;
        switch (s.trim().toLowerCase()) {
            case "v2": case "crc32": return V2;
            case "v3": case "crc32c": return V3;
            default: return null;
        }
    }

    public long compute(byte[] data, int off, int len, long seed) {
        java.util.zip.Checksum algo = this == V3 ? new CRC32C() : new CRC32();
        algo.update((int) seed);
        algo.update(data, off, len);
        return algo.getValue() & 0xFFFFFFFFL;
    }
}
