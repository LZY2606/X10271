package lab.jbd2.model;

/** JBD2 块公共头（big-endian: magic, type, sequence）。 */
public record BlockHeader(int magic, int type, long sequence) {
    public static final int MAGIC = 0xc03b3998;
    public static final int DESC = 1;
    public static final int COMMIT = 2;
    public static final int SB_V2 = 3;
    public static final int REVOKE = 4;

    public static BlockHeader read(byte[] b) {
        if (b.length < 12) return null;
        int magic = Hex.u32(b, 0);
        if (magic != MAGIC) return null;
        return new BlockHeader(magic, Hex.u32(b, 4), Hex.u32l(b, 8));
    }

    public static String typeName(int t) {
        return switch (t) {
            case DESC -> "descriptor";
            case COMMIT -> "commit";
            case SB_V2 -> "sb_v2";
            case REVOKE -> "revoke";
            default -> "unknown(" + t + ")";
        };
    }
}
