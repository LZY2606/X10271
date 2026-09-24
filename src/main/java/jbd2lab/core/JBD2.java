package jbd2lab.core;

/**
 * Wire-format constants for the lab's documented, self-contained JBD2 image variant.
 * Layout mirrors Linux JBD2 on-disk structures (journal_header2, journal_block_tag3,
 * journal_commit_header, journal_journal_head / revoke) so every parsed relation
 * (descriptor/data/commit/revoke, sequence, checksum version) has an explicit byte origin;
 * raw bytes are always preserved alongside the interpretation.
 */
public final class JBD2 {
    private JBD2() {}

    public static final int MAGIC = 0xC03B;

    public static final int DESC_BLOCK = 1;
    public static final int COMMIT_BLOCK = 2;
    public static final int SUPERBLOCK_V2 = 3;
    public static final int REVOKE_BLOCK = 5;

    public static final int HEADER_SIZE = 12;          // magic(u16), type(u16), sequence(u32), unused(u32)
    public static final int TAG_SIZE = 12;             // blocknr(u32), flags(u16), blocknr_high(u16), checksum(u32)
    public static final int DESC_TRAILER_SIZE = 4;     // crc32c over whole descriptor block, trailer field zeroed
    public static final int COMMIT_SIZE = 32;          // hdr(12) + chksum_type(u8) + padding(3) + pad(u32) + sec(u64) + crc(u32)
    public static final int COMMIT_CRC_OFF = 28;
    public static final int SB_CHKSUM_TYPE_OFF = 44;   // journal_superblock_s s_checksum_type (v2)

    public static final int TAG_ESCAPE_FLAG = 0x0008;
    public static final int TAG_FLAG_MASK = 0x000F;
    public static final int TAG_LAST_FLAG = 0x0004;    // JBD2_FLAG_* reserved bit reused in the lab as explicit last-tag
    public static final int TAG_IGNORE_CRC = 0x0010;   // tag's checksum field is not asserted (fixture marker)

    public static final int CHKSUM_NONE = 0;
    public static final int CHKSUM_CRC32 = 1;
    public static final int CHKSUM_CRC32C = 2;

    public static String blockTypeName(int type) {
        switch (type) {
            case DESC_BLOCK: return "descriptor";
            case COMMIT_BLOCK: return "commit";
            case SUPERBLOCK_V2: return "superblock_v2";
            case REVOKE_BLOCK: return "revoke";
            default: return "unknown(" + type + ")";
        }
    }
}
