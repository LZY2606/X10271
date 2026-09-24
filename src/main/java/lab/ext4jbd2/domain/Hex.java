package lab.ext4jbd2.domain;

import java.util.Base64;

public final class Hex {
    private Hex() {}

    public static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }

    public static String hex(byte[] data, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(data.length, max);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", data[i]));
        if (data.length > n) sb.append("…");
        return sb.toString();
    }

    public static String u32(long v) {
        return String.format("0x%08x", v & 0xFFFFFFFFL);
    }
}
