package org.fslab.jbd2.core;

/** Lower-case hex codec used for every preserved raw byte string. */
public final class Hex {
    private Hex() {}

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] decode(String hex) {
        String s = hex == null ? "" : hex.trim().replace(" ", "");
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("hex string has odd length: " + s.length());
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("non-hex character in: " + s);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
