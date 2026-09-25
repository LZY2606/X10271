package jbd2lab.store;

import java.util.Map;

/**
 * Machine-readable event persisted in the ledger. Immutable: accepting, rejecting
 * or adjusting parameters always appends a new event, never overwrites an old one.
 *
 * @param id        stable identity (content-hash + ordinal), preserved across export/import
 * @param ord       global ordering within a case (stable across export/import)
 * @param caseId    owning case
 * @param type      event type (see {@code EVENT_*})
 * @param ruleId    versioned rule that produced the verdict
 * @param verdict   accepted / rejected / uncertain / diagnostic / decision
 * @param adopted   whether this branch is selected by the current pointer
 * @param parentId  previous event on this branch (history navigation)
 * @param candidateOf  when non-null, an unselected candidate produced alongside parentId
 * @param versionId derivation version this event belongs to
 * @param payload   typed, JSON-serialisable details (display values + stable ids)
 */
public record LabEvent(
        String id,
        int ord,
        String caseId,
        String type,
        String ruleId,
        String verdict,
        boolean adopted,
        String parentId,
        String candidateOf,
        String versionId,
        String basis,
        Map<String, Object> payload) {

    public static final String TYPE_IMPORT = "import";
    public static final String TYPE_SCAN = "scan";
    public static final String TYPE_REPLAY = "replay";
    public static final String TYPE_BOUNDARY = "boundary";
    public static final String TYPE_REVOKE = "revoke_hit";
    public static final String TYPE_PARAM = "param_revision";
    public static final String TYPE_SOURCE = "source_edit";
    public static final String TYPE_DECISION = "human_decision";
    public static final String TYPE_INVALID = "invalid_quantity";
    public static final String TYPE_CRASH = "crash_point";
    public static final String TYPE_IDEMPOTENT = "idempotence";

    public LabEvent withAdopted(boolean value) {
        return new LabEvent(id, ord, caseId, type, ruleId, verdict, value, parentId,
                candidateOf, versionId, basis, payload);
    }
}
