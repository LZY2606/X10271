package lab.ext4.journal;

/** A value that cannot be converted stays at its origin and carries a diagnostic. */
public record Diagnostic(String code, String origin, String rawValue, String message) {
}
