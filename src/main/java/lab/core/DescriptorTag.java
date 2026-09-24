package lab.core;

public record DescriptorTag(long fsBlock, int flags) {
    public static final int FLAG_LAST = 0x4;
    public boolean last() { return (flags & FLAG_LAST) != 0; }
}
