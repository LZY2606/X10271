package lab.jbd2.model;

/** Append-only provenance event (imports, revisions, decisions, accept/reject). */
public record EventRecord(
        String eventId,
        String type,
        String atVersionId,
        String createdAt,
        String summary,
        String payloadJson) {
}
