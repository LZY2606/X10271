package org.jbd2lab.scan;

import org.jbd2lab.util.Units;

import java.util.List;

/** Full scan output: ring walk, all legal candidates, events and block-size diagnostics. */
public record ScanResult(
        String captureId,
        String paramsId,
        String ruleVersion,
        String resultId,
        String declaredBlockSizeRaw,
        Units.RawLong declaredBlockSize,
        int effectiveBlockSize,
        long journalStart,
        long ringSlots,
        int checksumVersion,
        long crashAtJournalBlock,
        List<ScanCandidate> candidates,
        List<LabEvent> events,
        List<String> diagnostics) {

    public ScanCandidate prefix() {
        return candidates.stream().filter(ScanCandidate::prefix).findFirst().orElseThrow();
    }
}
