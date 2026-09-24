package lab.jbd2.model;

/**
 * 描述块标签。dataPhys：标签之后的数据块在环内的物理块号（坐标）。
 * 块映射 blocknr 单位为文件系统块；flags: 1=LAST_TAG, 2=ESCAPE, 4=deleted（未映射）。
 */
public record DescriptorTag(int ordinal, long blocknr, boolean mapped,
                            boolean lastTag, boolean escape, int flags,
                            int tagOffset, long dataPhys) {
    public static final int FL_LAST = 1;
    public static final int FL_ESCAPE = 2;
    public static final int FL_DELETED = 4;
}
