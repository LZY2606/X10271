package lab.jbd2.model;

import java.util.List;

/**
 * 无法唯一决定时保留的全部合法候选（例如 checksum 失败之后的边界）。
 * selected 只记录人工/上游决定，未选候选永不物理删除。
 */
public record LegalCandidate(String candidateId, String branch, String boundary,
                             String rationale, String rule, boolean replayable,
                             List<String> affectedCoordinates,
                             String decisionEventId, String decision) {
    public LegalCandidate decided(String eventId, String decision) {
        return new LegalCandidate(candidateId, branch, boundary, rationale, rule,
                replayable, affectedCoordinates, eventId, decision);
    }
}
