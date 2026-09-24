package com.fslab.jbd2.model;

/** Parsed journal superblock (journal_superblock_s v2 subset), with raw retention. */
public record SuperblockInfo(
        long magic,
        int blocktype,
        int featureCompat,
        int featureIncompat,
        int featureRoCompat,
        long sStart,
        long maxLen,
        int checksumType,
        long storedChecksum,
        long computedChecksum,
        boolean checksumValid,
        String bytesHex) {

    public boolean is64bit() {
        return (featureIncompat & Constants.JBD2_FEATURE_INCOMPAT_64BIT) != 0;
    }

    public boolean isCsumV2() {
        return (featureIncompat & Constants.JBD2_FEATURE_INCOMPAT_CSUM_V2) != 0
                || checksumType == Constants.JBD2_CKSUM_V2_CRC32;
    }
}
