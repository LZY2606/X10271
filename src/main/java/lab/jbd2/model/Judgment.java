package lab.jbd2.model;

import java.util.List;
import java.util.Map;

/**
 * 机器可读裁定事件：把“为什么这样判”落成稳定结构，
 * 复核界面与反例档案直接读它，而不是重新推断。
 */
public record Judgment(String rule, String outcome, String severity,
                       String coordinate, String detail,
                       Map<String, String> evidence,
                       List<String> sourceChain) {
    public static final String R_COMMIT_GATE = "commit-gate";
    public static final String R_REVOKE_RANGE = "revoke-range";
    public static final String R_WRAP_ORDER = "wrap-order";
    public static final String R_CHECKSUM_BOUNDARY = "checksum-boundary";
    public static final String R_BLOCKSIZE = "blocksize-validity";
    public static final String R_CRASH_SLICE = "crash-slice";
    public static final String R_IDEMPOTENT = "replay-idempotency";

    public static Judgment of(String rule, String outcome, String severity,
                              String coordinate, String detail,
                              Map<String, String> evidence, List<String> chain) {
        return new Judgment(rule, outcome, severity, coordinate, detail,
                evidence == null ? Map.of() : Map.copyOf(evidence),
                chain == null ? List.of() : List.copyOf(chain));
    }
}
