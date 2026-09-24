package lab.jbd2.model;

/** 机器可读诊断：无法转换/异常一律留在原位置，绝不为画图丢弃。 */
public record Diagnostic(String code, String severity, String coordinate, String message) {
    public static Diagnostic error(String code, String coordinate, String message) {
        return new Diagnostic(code, "error", coordinate, message);
    }
    public static Diagnostic warn(String code, String coordinate, String message) {
        return new Diagnostic(code, "warn", coordinate, message);
    }
}
