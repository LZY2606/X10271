package com.fslab.jbd2.model;

/**
 * One journal_block_tag_s (v2, csum3 support off, flags before blocknr high).
 * Layout: u32 blocknr; u32 flags; [u32 blocknr_high when 64bit].
 */
public record DescriptorTag(
        long fsBlockNr,
        int flags,
        boolean escape,
        boolean sameUuid,
        boolean deleted,
        boolean lastTag,
        int tagIndex) {

    public static String id(long ringPos, int tagIndex) {
        return "tag@ring" + ringPos + "#" + tagIndex;
    }
}
