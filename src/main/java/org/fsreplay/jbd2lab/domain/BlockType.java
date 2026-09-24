package org.fsreplay.jbd2lab.domain;

/**
 * JBD2 journal block tags (the low 16 bits of h_blocktype). This laboratory
 * works with a faithful subset: descriptor / commit / revoke / data blocks.
 */
public enum BlockType {
    DESCRIPTOR(1, "descriptor block: tags describing the data blocks that follow"),
    COMMIT(2, "commit block: marks a transaction complete"),
    DATA(3, "raw data block payload, only interpretable after a descriptor"),
    SUPERBLOCK_V2(4, "journal superblock (not stored inside the ring)"),
    REVOKE_V2(5, "revoke block: filesystem blocks to suppress in replay"),
    UNKNOWN(0, "unrecognised tag");

    private final int code;
    private final String description;

    BlockType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static BlockType fromCode(int code) {
        for (BlockType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return UNKNOWN;
    }
}
