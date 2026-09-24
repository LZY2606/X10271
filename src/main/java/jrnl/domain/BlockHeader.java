package jrnl.domain;

/** Decoded JBD2 journal block header ({@code journal_header_s}). */
public record BlockHeader(int type, long sequence, int rawType, String typeName, String diagnostic) {

    public static final int DESCRIPTOR = 1;
    public static final int COMMIT_BLOCK = 2;
    public static final int SUPERBLOCK_V2 = 3;
    public static final int REVOKE_BLOCK = 4;

    public static String typeName(int t) {
        return switch (t) {
            case DESCRIPTOR -> "descriptor";
            case COMMIT_BLOCK -> "commit";
            case SUPERBLOCK_V2 -> "journal-superblock-v2";
            case REVOKE_BLOCK -> "revoke";
            default -> "data-or-unknown(type=" + t + ")";
        };
    }
}
