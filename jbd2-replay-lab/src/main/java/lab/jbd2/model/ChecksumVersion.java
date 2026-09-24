package lab.jbd2.model;

/** Checksum flavour declared by the journal superblock. */
public enum ChecksumVersion {
    NONE(0, "none"),
    CRC32(1, "crc32"),
    CRC32C(2, "crc32c");

    public final int code;
    public final String label;

    ChecksumVersion(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public static ChecksumVersion fromCode(int code) {
        for (ChecksumVersion v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        return null;
    }
}
