package com.fslab.jbd2.model;

/** One ordered step of the replay explanation (block coverage order). */
public record ReplayStep(
        int orderIndex,
        ReplayOpKind kind,
        long sequence,
        Long fsBlockNr,
        Long ringPos,
        Long revokeSequence,
        String blockValueFingerprint,
        String explanation) {

    public String id() {
        return "replay-step#" + orderIndex;
    }
}
