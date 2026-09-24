package journal.core;

import java.util.zip.CRC32;

/** Checksum algorithms selectable through the journal superblock checksum version. */
public final class Checksums {
    private Checksums() {}

    public static long of(int version, byte[] data) {
        switch (version) {
            case JournalFormat.CKSUM_NONE: return 0L;
            case JournalFormat.CKSUM_CRC32: {
                CRC32 c = new CRC32();
                c.update(data);
                return c.getValue();
            }
            case JournalFormat.CKSUM_CRC32C:
                return crc32c(data);
            default:
                throw new IllegalArgumentException("unknown checksum version " + version);
        }
    }

    private static final int[] CRC32C_TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0x82F63B78 ^ (c >>> 1) : c >>> 1;
            }
            CRC32C_TABLE[n] = c;
        }
    }

    public static long crc32c(byte[] data) {
        int c = 0xFFFFFFFF;
        for (byte v : data) {
            c = CRC32C_TABLE[(c ^ v) & 0xFF] ^ (c >>> 8);
        }
        return (c ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }
}
