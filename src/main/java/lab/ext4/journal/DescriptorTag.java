package lab.ext4.journal;

public record DescriptorTag(long fsBlockNo, int flags) {
    public boolean isLast() {
        return (flags & JournalFormat.FLAG_LAST_TAG) != 0;
    }

    public boolean isDeleted() {
        return (flags & JournalFormat.FLAG_DELETED) != 0;
    }
}
