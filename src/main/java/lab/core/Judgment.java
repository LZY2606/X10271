package lab.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lab.core.JsonUtil.JN;

/** 机器可读取的判定事件。rule 是稳定规则标识，subject 指向受影响对象。 */
public record Judgment(String rule, String subject, String decision, String reason) {

    public ObjectNode toJson() {
        ObjectNode n = JN.obj();
        n.put("rule", rule);
        n.put("subject", subject);
        n.put("decision", decision);
        n.put("reason", reason);
        return n;
    }

    public static Judgment of(String rule, String subject, String decision, String reason) {
        return new Judgment(rule, subject, decision, reason);
    }
}
