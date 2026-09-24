package com.fslab.jbd2.model;

/**
 * A block at a physical journal coordinate. Original bytes are always retained
 * (bytesHex); normalization into a role is derivation-layer information.
 */
public record JournalBlock(
        long physicalBlockNr,
        long ringPosition,
        String bytesHex,
        BlockRole role,
        Long headerSequence,
        Long headerChecksum,
        String note) {

    public static String id(long ringPosition) {
        return "jb@ring" + ringPosition;
    }
}
