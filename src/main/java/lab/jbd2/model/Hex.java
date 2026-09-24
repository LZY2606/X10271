package lab.jbd2.model;

import java.util.Arrays;

/** 字节 <-> 小写十六进制工具。 */
public final class Hex {
    private static final char[] C = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String enc(byte[] b) {
        if (b == null) return "";
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            out[i * 2] = C[v >>> 4];
            out[i * 2 + 1] = C[v & 0xf];
        }
        return new String(out);
    }

    public static byte[] dec(String s) {
        String t = s.trim();
        if ((t.length() & 1) != 0) throw new IllegalArgumentException("奇数长度十六进制: " + s);
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(t.charAt(i * 2), 16);
            int lo = Character.digit(t.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("非法十六进制: " + s);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static byte[] slice(byte[] b, int off, int len) {
        return Arrays.copyOfRange(b, off, Math.min(off + len, b.length));
    }

    public static int u32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    public static long u32l(byte[] b, int off) {
        return u32(b, off) & 0xffffffffL;
    }

    public static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    public static boolean magicAt(byte[] b, int magic) {
        return b.length >= 4 && u32(b, 0) == magic;
    }
}
