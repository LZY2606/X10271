package org.jbd2lab.util;

/** Hexadecimal helpers. All on-disk integers in the JBD2-LAB dialect are big-endian. */
public final class Hex {
    private Hex() {}

    public static String encode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] decode(String hex) {
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        if ((h.length() & 1) != 0) {
            throw new IllegalArgumentException("hex input has odd length: " + hex);
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("non-hex character in: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Stable short identifier used in UI labels; the full id stays the source of truth. */
    public static String shortId(String id) {
        int cut = id.indexOf(':');
        String body = cut >= 0 ? id.substring(cut + 1) : id;
        return body.length() <= 12 ? body : body.substring(0, 12);
    }
}
