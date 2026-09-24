package jrnl.store;

import jrnl.domain.ChecksumVersion;

import java.util.List;

/**
 * Raw import bundle: original representations are preserved verbatim
 * (blockSizeRaw, checksumRaw, ringStartRaw, crash points as text).
 */
public record SnapshotInput(
        String caseId,
        List<BlockInput> blocks,
        int ringStartImage,
        String ringStartRaw,
        String blockSizeRaw,
        String checksumRaw,
        List<CrashInput> crashPoints,
        String note) {

    public record BlockInput(int imageIndex, String hex, String sourceRef, String kind, String note) {
    }

    public record CrashInput(int imageIndex, String rawRepr, String diagnostic) {
    }

    public ChecksumVersion checksumVersion() {
        return ChecksumVersion.fromRaw(checksumRaw);
    }
}
