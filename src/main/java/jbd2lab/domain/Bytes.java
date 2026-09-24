package jbd2lab.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Hex / checksum helpers. All byte content is preserved as bytes; hex is only a display form. */
public final class Bytes {
    private Bytes() {}

    public static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }

    public static String hex(byte[] data, int from, int len) {
        return HexFormat.of().formatHex(data, from, from + len);
    }

    public static byte[] unhex(String hex) {
        return HexFormat.of().parseHex(hex.replaceAll("\\s+", ""));
    }

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long getBE32(byte[] b, int off) {
        return ((b[off] & 0xffL) << 24) | ((b[off + 1] & 0xffL) << 16)
                | ((b[off + 2] & 0xffL) << 8) | (b[off + 3] & 0xffL);
    }

    public static void putBE32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static long getBE64(byte[] b, int off) {
        long hi = getBE32(b, off);
        long lo = getBE32(b, off + 4);
        return (hi << 32) | lo;
    }

    public static void putBE64(byte[] b, int off, long v) {
        putBE32(b, off, v >>> 32);
        putBE32(b, off + 4, v & 0xffffffffL);
    }
}
