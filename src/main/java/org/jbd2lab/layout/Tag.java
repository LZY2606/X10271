package org.jbd2lab.layout;

/** One descriptor tag: which filesystem block the following data block contains. */
public record Tag(long fsBlock, int flags, int fileOffset, boolean deleted, boolean escape,
                  boolean lastTag, long tagCrc) {

    public boolean sameUuid() {
        return (flags & Jbd2.TAG_FLAG_SAME_UUID) != 0;
    }
}
