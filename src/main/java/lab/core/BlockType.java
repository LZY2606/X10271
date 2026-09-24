package lab.core;

/** JBD2 日志块类型（数值沿用内核定义）。 */
public enum BlockType {
    UNKNOWN(0),
    DESCRIPTOR(1),
    COMMIT(2),
    SUPERBLOCK(3),
    REVOKE(4),
    DATA(5);

    public final int code;

    BlockType(int code) { this.code = code; }

    public static BlockType of(int code) {
        for (BlockType t : values()) if (t.code == code) return t;
        return UNKNOWN;
    }
}
