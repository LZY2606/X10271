package lab.jbd2.model;

import java.util.List;

/**
 * Machine-readable adjudication event. {@code rule} is a stable rule identifier and
 * {@code inputs} are stable identities of source/derived objects that justify the
 * verdict, so every displayed conclusion can be traced back to its evidence.
 */
public record RuleEvent(
        String eventId,
        String kind,
        String rule,
        String severity,
        String message,
        List<String> inputs,
        String subject,
        long orderIndex) {

    public static RuleEvent of(String kind, String rule, String severity, String message,
                               String subject, List<String> inputs, long order) {
        String id = "ev-" + kind + "-" + (subject == null ? "g" : sanitize(subject))
                + "-" + Long.toHexString(((long) (kind + rule + subject + order).hashCode()) & 0xffffffffL);
        return new RuleEvent(id, kind, rule, severity, message, inputs, subject, order);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
