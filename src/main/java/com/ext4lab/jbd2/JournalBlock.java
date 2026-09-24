package com.ext4lab.jbd2;

import java.util.ArrayList;
import java.util.List;

/** One parsed journal block in ring-walk order. */
public final class JournalBlock {
    public static final class Tag {
        public long fsBlock;        // target filesystem block number
        public int checksum;        // stored tag checksum (low 16 bits)
        public int flags;
        public int dataRingIndex;   // journal block index holding the data
        public boolean dataChecksumOk;
        public byte[] data;         // raw data block bytes (preserved)
    }

    public int slot;                // position in ring walk (0-based)
    public int ringIndex;           // absolute block index inside journal image
    public int type;
    public long sequence;
    public byte[] raw;              // original block bytes, preserved
    public boolean untrusted;       // beyond a checksum boundary
    public final List<Tag> tags = new ArrayList<>();
    public final List<Long> revokeBlocks = new ArrayList<>();
    public boolean commitChecksumOk;

    public String typeName() { return JournalFormat.typeName(type); }
}
