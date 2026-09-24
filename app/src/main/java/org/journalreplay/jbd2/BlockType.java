package org.journalreplay.jbd2;

/** JBD2 journal block types (h_blocktype in the block header). */
public enum BlockType {
    DESCRIPTOR(1),
    COMMIT(2),
    SUPERBLOCK_V1(3),
    SUPERBLOCK_V2(4),
    REVOKE(5),
    DATA(0); // data blocks carry no JBD2 header; inferred from descriptor tags

    public final int code;

    BlockType(int code) {
        this.code = code;
    }

    public static BlockType fromCode(int code) {
        for (BlockType t : values()) {
            if (t.code == code && t != DATA) return t;
        }
        return DATA;
    }
}
