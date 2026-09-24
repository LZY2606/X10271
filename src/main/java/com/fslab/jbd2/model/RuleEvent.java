package com.fslab.jbd2.model;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable judgment record. Each core semantic becomes an event with a
 * stable rule id, the subject objects, structured facts and a human explanation,
 * so the review UI can render why a result is valid, replayed or uncertain.
 */
public record RuleEvent(
        String eventId,
        String ruleId,
        String level,
        List<String> subjectRefs,
        Map<String, Object> facts,
        String explanation) {

    public static String id(String ruleId, String suffix) {
        return "rule-event:" + ruleId + ":" + suffix;
    }
}
