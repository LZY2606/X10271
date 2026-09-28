package org.fslab.jbd2.core;

import java.nio.charset.StandardCharsets;

/** Tiny little-endian byte buffer reader over a preserved raw block. */
public final class Buffer {
    private final byte[] b;
    private final int base;

    public Buffer(byte[] block) {
        this(block, 0);
    }

    private Buffer(byte[] block, int base) {
        this.b = block;
        this.base = base;
    }

    public byte[] array() { return b; }

    public int intAt(int off) {
        int p = base + off;
        return (b[p] & 0xFF)
                | ((b[p + 1] & 0xFF) << 8)
                | ((b[p + 2] & 0xFF) << 16)
                | ((b[p + 3] & 0xFF) << 24);
    }

    public long uintAt(int off) {
        return intAt(off) & 0xFFFFFFFFL;
    }

    public byte byteAt(int off) {
        return b[base + off];
    }

    public String hexAt(int off, int len) {
        byte[] slice = new byte[len];
        System.arraycopy(b, base + off, slice, 0, len);
        return Hex.encode(slice);
    }

    public String asciiAt(int off, int len) {
        byte[] slice = new byte[len];
        System.arraycopy(b, base + off, slice, 0, len);
        return new String(slice, StandardCharsets.US_ASCII).trim();
    }

    public byte[] slice(int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, base + off, out, 0, len);
        return out;
    }
}
