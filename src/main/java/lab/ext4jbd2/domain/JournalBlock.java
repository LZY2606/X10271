package lab.ext4jbd2.domain;

import java.util.ArrayList;
import java.util.List;

/** A parsed journal block. Raw bytes are always retained. */
public final class JournalBlock {
    public static final class Tag {
        public final long fsBlock;   // fs-block-index basis
        public final int flags;
        public final long tagCrc;

        public Tag(long fsBlock, int flags, long tagCrc) {
            this.fsBlock = fsBlock;
            this.flags = flags;
            this.tagCrc = tagCrc;
        }

        public boolean isLast() { return (flags & Jbd2.FLAG_LAST_TAG) != 0; }
    }

    public final int coordinate;        // journal-block-index within the journal area
    public final int type;              // -1 when the header magic does not match
    public final long sequence;         // commit sequence carried by the header
    public final List<Tag> tags = new ArrayList<>();
    public final List<Long> revokeBlocks = new ArrayList<>(); // fs-block-index basis
    public Boolean checksumOk;    // null when checksum version v0 (no checksum)
    public final String parseError;     // non-null when the block could not be parsed
    public final byte[] raw;

    public JournalBlock(int coordinate, int type, long sequence, Boolean checksumOk,
                        String parseError, byte[] raw) {
        this.coordinate = coordinate;
        this.type = type;
        this.sequence = sequence;
        this.checksumOk = checksumOk;
        this.parseError = parseError;
        this.raw = raw;
    }

    public boolean hasMagic() { return type > 0; }
}
