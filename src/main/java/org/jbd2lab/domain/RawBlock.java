package org.jbd2lab.domain;

import org.jbd2lab.util.Hex;
import org.jbd2lab.util.Ids;

import java.util.Arrays;

/**
 * One imported block. Coordinates and the exact original bytes are retained;
 * {@code physicalRingIndex} is the journal-ring slot index (unit journal-block) starting at
 * {@code journalStart}, never a filesystem block number.
 */
public final class RawBlock {
    private final long physicalRingIndex;
    private final byte[] bytes;
    private final String origin;

    public RawBlock(long physicalRingIndex, byte[] bytes, String origin) {
        this.physicalRingIndex = physicalRingIndex;
        this.bytes = bytes.clone();
        this.origin = origin;
    }

    public long physicalRingIndex() { return physicalRingIndex; }
    public byte[] bytes() { return bytes.clone(); }
    byte[] bytesRef() { return bytes; }
    public String origin() { return origin; }
    public String sha256() { return Ids.sha256Hex(bytes); }

    @Override public boolean equals(Object o) {
        return o instanceof RawBlock rb && rb.physicalRingIndex == physicalRingIndex
                && Arrays.equals(rb.bytes, bytes);
    }
    @Override public int hashCode() {
        return 31 * Long.hashCode(physicalRingIndex) + Arrays.hashCode(bytes);
    }
}
