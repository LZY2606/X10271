package lab.jbd2.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Machine-readable judgment event. Every rule application that keeps, excludes
 * or defers a result emits one of these so the archive can explain itself.
 */
public record JournalEvent(String id, String rule, String subject, String decision, String basis) {

    public static JournalEvent of(String rule, String subject, String decision, String basis) {
        return new JournalEvent(UUID.randomUUID().toString(), rule, subject, decision, basis);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("rule", rule);
        m.put("subject", subject);
        m.put("decision", decision);
        m.put("basis", basis);
        return m;
    }

    public static JournalEvent fromMap(Map<String, Object> m) {
        return new JournalEvent(
                String.valueOf(m.get("id")),
                String.valueOf(m.get("rule")),
                String.valueOf(m.get("subject")),
                String.valueOf(m.get("decision")),
                String.valueOf(m.get("basis")));
    }
}
