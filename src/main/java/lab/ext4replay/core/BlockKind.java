package lab.ext4replay.core;

public enum BlockKind {
    SUPERBLOCK(4),
    DESCRIPTOR(1),
    COMMIT(2),
    REVOKE(5),
    DATA(0),
    UNKNOWN(-1);

    public final int typeCode;

    BlockKind(int typeCode) {
        this.typeCode = typeCode;
    }
}
