package com.fslab.jbd2.model;

/** Final filesystem-block state derived at the selected crash point. */
public record DerivedBlock(
        long fsBlockNr,
        long sourceSequence,
        long sourceRingPos,
        String bytesHex,
        String fingerprint,
        String provenance) {

    public static String id(long fsBlockNr) {
        return "derived-fsblock:" + fsBlockNr;
    }
}
