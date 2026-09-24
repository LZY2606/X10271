package fsreplay.domain;

import java.util.ArrayList;
import java.util.List;

/** One physical block of the journal ring, in ring-walk order. */
public final class JournalBlock {
    public enum Type { DESCRIPTOR, DATA, COMMIT, REVOKE, UNKNOWN }

    /** Descriptor tag: fs block number + flags + expected data checksum. */
    public static final class Tag {
        public final long fsBlock;
        public final int flags;
        public final long checksum;
        public Tag(long fsBlock, int flags, long checksum) {
            this.fsBlock = fsBlock; this.flags = flags; this.checksum = checksum;
        }
        public boolean last() { return (flags & 0x8) != 0; }
    }

    public final int ringIndex;      // position in ring-walk order (0 = ring start)
    public final int physicalBlock;  // physical block number inside the journal area
    public final Type type;
    public final long sequence;      // -1 for DATA (inherits from its descriptor)
    public final List<Tag> tags = new ArrayList<>();
    public final List<Long> revoked = new ArrayList<>();
    public long commitChecksum = -1;
    /** for DATA blocks: fs block this data belongs to, -1 if unknown */
    public long dataFsBlock = -1;
    public long dataTxSeq = -1;

    public JournalBlock(int ringIndex, int physicalBlock, Type type, long sequence) {
        this.ringIndex = ringIndex;
        this.physicalBlock = physicalBlock;
        this.type = type;
        this.sequence = sequence;
    }
}
