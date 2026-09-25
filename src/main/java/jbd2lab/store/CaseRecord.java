package jbd2lab.store;

/** Case identity plus its current derivation-version pointer. */
public record CaseRecord(String id, String name, String currentVersion, long baseVersion,
                         String createdAt) {
}
