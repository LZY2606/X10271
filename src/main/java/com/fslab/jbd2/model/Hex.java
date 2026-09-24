package com.fslab.jbd2.model;

public final class Hex {
    private Hex() {}
    private static final char[] H = "0123456789abcdef".toCharArray();

    public static String encode(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = H[v >>> 4];
            out[i * 2 + 1] = H[v & 0xF];
        }
        return new String(out);
    }

    public static byte[] decode(String hex) {
        String s = hex.replaceAll("\\s+", "");
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Stable short fingerprint for display without losing the retained original bytes. */
    public static String finger(byte[] data, int head) {
        int n = Math.min(head, data.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(H[(data[i] & 0xFF) >>> 4]).append(H[data[i] & 0xF]);
        }
        if (data.length > head) sb.append(" … ").append(data.length).append("B");
        return sb.toString();
    }
}
