package gsb.derive;

import com.fasterxml.jackson.databind.JsonNode;

/** 一个派生节点的落库结果：输入摘要 + 状态 + 人类可读原因 + 结果 JSON。 */
public record DerivedRow(String node, String inputHash, String status, String reason, JsonNode result) {
    public static final String VALID = "valid";
    public static final String COMPUTED = "computed";
}
