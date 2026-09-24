package lab.jbd2.core;

import java.util.ArrayList;
import java.util.List;

/** One parsed journal block. Raw bytes are always preserved. */
public final class JournalBlock {
    public enum Type { DESCRIPTOR, DATA, COMMIT, REVOKE, SUPERBLOCK_V1, SUPERBLOCK_V2, UNKNOWN }

    public record Tag(long fsBlock, int flags, boolean last) {}

    public int ringIndex = -1;
    public Type type = Type.UNKNOWN;
    public long sequence;
    public final List<Tag> tags = new ArrayList<>();
    public final List<Long> revokeRecords = new ArrayList<>();
    public boolean checksumChecked;
    public boolean checksumOk;
    public boolean headerValid;
    public byte[] raw = new byte[0];

    public String rawHex() {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
