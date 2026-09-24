package org.jbd2lab.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable, content-addressed identity.
 *
 * <p>Raw inputs keep an intrinsic hash ("sha256:..." of their exact bytes / canonical text).
 * Derived objects use "derived:" + short sha256 of canonical input references + rule version +
 * own coordinate. These ids survive export/import and database deletion.
 */
public final class Ids {
    private Ids() {}

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Hex.encode(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String ofRaw(String kind, String stableName, byte[] payload) {
        return kind + ":" + sha256Hex((stableName + "\u0000").getBytes(StandardCharsets.UTF_8))
                .substring(0, 8) + sha256Hex(payload).substring(0, 24);
    }

    /** Deterministic id for a derived object. Same inputs and rule => same id. */
    public static String derived(String kind, String canonicalBasis) {
        return "derived-" + kind + "-" + sha256Hex(canonicalBasis.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }
}
