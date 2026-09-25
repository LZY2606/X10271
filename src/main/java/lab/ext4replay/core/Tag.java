package lab.ext4replay.core;

/** jbd2 描述块标签（journal_block_tag_s 子集，12 字节）。 */
public final class Tag {
    public static final int FLAG_ESCAPE = 0x1;
    public static final int FLAG_SAME_UUID = 0x2;
    public static final int FLAG_DELETED = 0x4;
    public static final int FLAG_LAST_TAG = 0x8;

    public final long fsBlock;      // 文件系统块号（文件系统坐标系）
    public final int flags;
    public final int tagChecksum;

    public Tag(long fsBlock, int flags, int tagChecksum) {
        this.fsBlock = fsBlock;
        this.flags = flags;
        this.tagChecksum = tagChecksum;
    }

    public boolean isLast() {
        return (flags & FLAG_LAST_TAG) != 0;
    }

    public boolean escaped() {
        return (flags & FLAG_ESCAPE) != 0;
    }
}
