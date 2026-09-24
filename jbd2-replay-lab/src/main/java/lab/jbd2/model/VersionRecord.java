package lab.jbd2.model;

/** One immutable derivation version; only one version is current at any time. */
public record VersionRecord(
        String versionId,
        String parentVersionId,
        String eventId,
        String label,
        String createdAt,
        String status) {
}
