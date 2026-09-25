package jbd2lab.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable content identity helpers. */
public final class Hashes {
    private Hashes() {
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
