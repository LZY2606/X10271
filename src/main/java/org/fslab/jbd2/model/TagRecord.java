package org.fslab.jbd2.model;

/**
 * One descriptor tag. {@code fileBlock} is the filesystem-block coordinate the
 * following data block would be replayed onto. All coordinates are labelled
 * with their basis at the call site.
 */
public record TagRecord(
        int tagIndex,
        int tagOffset,
        long fileBlock,
        int flags,
        boolean sameUuid,
        boolean escape,
        boolean deleted,
        boolean lastTag,
        String tagChecksumHex,
        Boolean tagChecksumOk,
        Integer dataPhysicalBlock) {

    public boolean valid() {
        return dataPhysicalBlock != null;
    }
}
