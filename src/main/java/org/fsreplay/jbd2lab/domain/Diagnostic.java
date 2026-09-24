package org.fsreplay.jbd2lab.domain;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * A non-fatal note attached to an imported or derived object. Unconvertible
 * input, checksum failures and replay uncertainty all become diagnostics
 * instead of silent omissions.
 */
@JsonPropertyOrder({"severity", "code", "message", "at"})
public final class Diagnostic {
    public enum Severity { ERROR, WARNING, UNCERTAIN, INFO }

    private final Severity severity;
    private final String code;
    private final String message;
    private final String at;

    public Diagnostic(Severity severity, String code, String message, String at) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.at = at;
    }

    public static Diagnostic error(String code, String message, String at) {
        return new Diagnostic(Severity.ERROR, code, message, at);
    }

    public static Diagnostic warn(String code, String message, String at) {
        return new Diagnostic(Severity.WARNING, code, message, at);
    }

    public static Diagnostic uncertain(String code, String message, String at) {
        return new Diagnostic(Severity.UNCERTAIN, code, message, at);
    }

    public static Diagnostic info(String code, String message, String at) {
        return new Diagnostic(Severity.INFO, code, message, at);
    }

    public Severity severity() { return severity; }
    public String code() { return code; }
    public String message() { return message; }
    public String at() { return at; }

    @Override
    public String toString() {
        return "[" + severity + " " + code + "@" + at + "] " + message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return o;
        if (!(o instanceof Diagnostic d)) return false;
        return severity == d.severity && Objects.equals(code, d.code)
                && Objects.equals(message, d.message) && Objects.equals(at, d.at);
    }

    @Override
    public int hashCode() {
        return Objects.hash(severity, code, message, at);
    }
}
