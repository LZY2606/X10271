package jbd2lab.store;

/** Immutable derivation version with provenance over inputs and the rule bundle. */
public record DerivationVersion(String id, String caseId, long versionNo,
                                String parentVersionId, String ruleId,
                                String inputsHash, String resultJson,
                                String createdBy, boolean complete) {
}
