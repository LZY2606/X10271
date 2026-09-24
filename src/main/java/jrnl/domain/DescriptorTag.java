package jrnl.domain;

/**
 * One descriptor tag: maps the following journal data block to a filesystem
 * block number, carrying the raw flag bits and the 64-bit high word presence.
 */
public record DescriptorTag(long fsBlock, int flags, boolean high64, boolean sameUuid, boolean deleted) {

    public static final int FLAG_ESCAPE = 1;
    public static final int FLAG_SAME_UUID = 2;
    public static final int FLAG_DELETED = 4;
    public static final int FLAG_LAST_TAG = 8;

    public String flagText() {
        StringBuilder sb = new StringBuilder();
        if (escape()) sb.append("ESCAPE,");
        if (sameUuid) sb.append("SAME_UUID,");
        if (deleted) sb.append("DELETED,");
        if (lastTag()) sb.append("LAST_TAG,");
        return sb.isEmpty() ? "-" : sb.substring(0, sb.length() - 1);
    }

    public boolean escape() {
        return (flags & FLAG_ESCAPE) != 0;
    }

    public boolean lastTag() {
        return (flags & FLAG_LAST_TAG) != 0;
    }
}
