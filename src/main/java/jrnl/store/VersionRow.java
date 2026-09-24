package jrnl.store;

public record VersionRow(
        String versionId,
        String caseId,
        String parentVersionId,
        String label,
        String status,
        String blockSizeRaw,
        Long blockSizeBytes,
        String blockSizeDiag,
        String checksumRaw,
        String checksumVersion,
        int ringStartImage,
        String ringStartRaw,
        String decisionJson,
        String createdAt) {
}
