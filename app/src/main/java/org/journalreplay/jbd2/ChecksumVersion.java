package org.journalreplay.jbd2;

/** Journal checksum formats. Raw version numbers are preserved from the superblock. */
public enum ChecksumVersion {
    V1_CRC16(1),
    V2_CRC32C(2),
    V3_CRC32C_PER_TAG(3);

    public final int code;

    ChecksumVersion(int code) {
        this.code = code;
    }

    public static ChecksumVersion fromCode(int code) {
        for (ChecksumVersion v : values()) {
            if (v.code == code) return v;
        }
        return null; // unknown versions stay raw; callers attach a diagnostic
    }
}
