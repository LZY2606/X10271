package jrnl.store;

/** One immutable, machine-readable event. History is never rewritten. */
public record EventRow(
        String eventId,
        String caseId,
        long seq,
        String kind,
        String versionId,
        String parentEventId,
        String baseEventId,
        String candidateOfEventId,
        boolean chosen,
        String title,
        String rationale,
        String payloadJson,
        String createdAt) {
}
