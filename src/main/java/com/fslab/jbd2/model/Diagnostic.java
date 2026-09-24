package com.fslab.jbd2.model;

import java.util.List;

public record Diagnostic(String code, String severity, String message, List<String> refs) {
    public static Diagnostic error(String code, String message, List<String> refs) {
        return new Diagnostic(code, "ERROR", message, refs);
    }
    public static Diagnostic warn(String code, String message, List<String> refs) {
        return new Diagnostic(code, "WARN", message, refs);
    }
    public static Diagnostic info(String code, String message, List<String> refs) {
        return new Diagnostic(code, "INFO", message, refs);
    }
}
