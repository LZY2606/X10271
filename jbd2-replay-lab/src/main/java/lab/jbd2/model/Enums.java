package lab.jbd2.model;

/** On-disk block header types used by the lab JBD2 image (values match the kernel). */
public enum BlockKind {
    SUPERBLOCK(4),
    DESCRIPTOR(1),
    COMMIT(2),
    REVOKE(5),
    DATA(0);

    public final int code;

    BlockKind(int code) {
        this.code = code;
    }

    public static BlockKind fromCode(int code) {
        for (BlockKind k : values()) {
            if (k.code == code) {
                return k;
            }
        }
        return null;
    }
}
