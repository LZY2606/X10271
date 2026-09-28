package org.fslab.jbd2.model;

/** A diagnostic kept next to the object it refers to; nothing is dropped to make a picture. */
public record Diagnostic(
        String code,
        String severity,
        String location,
        String message,
        String originalValue) {

    public static Diagnostic error(String code, String location, String message, String original) {
        return new Diagnostic(code, "ERROR", location, message, original);
    }

    public static Diagnostic warn(String code, String location, String message, String original) {
        return new Diagnostic(code, "WARN", location, message, original);
    }
}
