package com.ext4lab.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable content-addressed identities. Same content -> same id, across export/import. */
public final class Ids {
    private Ids() {}

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Content id with a short type prefix, e.g. mat:9f2c1a4b7e01 */
    public static String contentId(String prefix, String canonicalContent) {
        return prefix + ":" + sha256(canonicalContent).substring(0, 16);
    }
}
