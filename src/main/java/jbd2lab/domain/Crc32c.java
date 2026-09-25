package jbd2lab.domain;

/**
 * CRC-32C (Castagnoli), the checksum family used by JBD2 metadata checksums
 * (jbd2_journal_revoke/csum with superblock feature JBD2_FEATURE_INCOMPAT_CSUM_V2/V3).
 * Self-contained table-driven implementation so fixtures and replay share one family.
 */
public final class Crc32c {
    private static final int[] TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = ((c & 1) != 0) ? (0x82F63B78 ^ (c >>> 1)) : (c >>> 1);
            }
            TABLE[n] = c;
        }
    }

    private Crc32c() {
    }

    public static int of(byte[] data) {
        return update(0xFFFFFFFF, data, 0, data.length) ^ 0xFFFFFFFF;
    }

    public static int of(byte[]... parts) {
        int crc = 0xFFFFFFFF;
        for (byte[] p : parts) {
            crc = update(crc, p, 0, p.length);
        }
        return crc ^ 0xFFFFFFFF;
    }

    public static int update(int crc, byte[] data, int off, int len) {
        int c = crc;
        for (int i = off; i < off + len; i++) {
            c = TABLE[(c ^ data[i]) & 0xFF] ^ (c >>> 8);
        }
        return c;
    }
}
