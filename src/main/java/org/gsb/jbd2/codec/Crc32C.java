package org.gsb.jbd2.codec;

public final class Crc32C {
    private static final int[] TABLE = new int[256];
    private int value = 0xFFFFFFFF;

    static {
        for (int index = 0; index < 256; index++) {
            int crc = index;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) == 0 ? crc >>> 1 : (crc >>> 1) ^ 0x82F63B78;
            }
            TABLE[index] = crc;
        }
    }

    public void update(byte[] bytes, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            value = TABLE[(value ^ bytes[index]) & 0xFF] ^ (value >>> 8);
        }
    }

    public long getValue() {
        return (value ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }
}
