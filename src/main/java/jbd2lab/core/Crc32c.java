package jbd2lab.core;

/**
 * Pure-Java CRC32C (Castagnoli, poly 0x1EDC6F41 reflected, init/xorout 0xFFFFFFFF),
 * matching JBD2's crc32c checksums. No native dependency so fixtures stay reproducible.
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

    private int crc = 0xFFFFFFFF;

    public void update(byte[] buf, int off, int len) {
        int c = crc;
        for (int i = off; i < off + len; i++) {
            c = TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
        }
        crc = c;
    }

    public void update(byte[] buf) { update(buf, 0, buf.length); }

    public int value() { return crc ^ 0xFFFFFFFF; }

    public static int of(byte[] buf) {
        Crc32c c = new Crc32c();
        c.update(buf);
        return c.value();
    }

    public static int of(byte[] buf, int off, int len) {
        Crc32c c = new Crc32c();
        c.update(buf, off, len);
        return c.value();
    }
}
