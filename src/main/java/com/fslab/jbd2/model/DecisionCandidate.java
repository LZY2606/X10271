package com.fslab.jbd2.model;

import java.util.List;

/**
 * One legal interpretation retained when a point cannot be uniquely decided
 * (e.g. where the valid log ends after a checksum failure). Candidates are never
 * collapsed silently; a reviewer chooses one and the rejected one stays on record.
 */
public record DecisionCandidate(
        String candidateId,
        String kind,
        long sequence,
        Long replayThroughSequence,
        boolean includesStrayTail,
        String label,
        String rationale,
        List<String> evidenceRefs,
        boolean chosen) {

    public static String id(long sequence, String suffix) {
        return "cand@seq" + sequence + "/" + suffix;
    }
}
