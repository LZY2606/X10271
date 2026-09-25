package jbd2lab.store;

/**
 * Stored source material with raw representation intact. {@code rawJson} holds the
 * original imported object (original strings/units); {@code kind} distinguishes
 * blocks / superblock / crashpoint / params.
 */
public record MaterialRecord(String id, String caseId, String kind, String ref,
                             String rawJson, String sha256, long version) {
}
