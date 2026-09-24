package com.journalreplay.domain;

import java.util.List;

/** Parsed journal blocks. Raw bytes are retained in every variant. */
public sealed interface Blocks {

    /** Journal ring coordinate of this block (unit: block, basis: journal area start). */
    int loc();

    byte[] raw();

    long seq();

    record Tag(long fsBlock, int flags, boolean escaped, boolean lastTag,
               Long storedChecksum, Long computedChecksum) {
        public boolean checksumOk() {
            return storedChecksum == null || storedChecksum.equals(computedChecksum);
        }
    }

    record Descriptor(int loc, byte[] raw, long seq, List<Tag> tags, int dataStartLoc,
                      Long storedTailChecksum, Long computedTailChecksum) implements Blocks {
        public boolean tailOk() {
            return storedTailChecksum == null || storedTailChecksum.equals(computedTailChecksum);
        }
    }

    record Commit(int loc, byte[] raw, long seq,
                  Long storedChecksum, Long computedChecksum) implements Blocks {
        public boolean checksumOk() {
            return storedChecksum == null || storedChecksum.equals(computedChecksum);
        }
    }

    record Revoke(int loc, byte[] raw, long seq, List<Long> blocks,
                  Long storedTailChecksum, Long computedTailChecksum) implements Blocks {
        public boolean tailOk() {
            return storedTailChecksum == null || storedTailChecksum.equals(computedTailChecksum);
        }
    }

    /** A block whose magic/type/sequence does not fit the expected position. */
    record Unparseable(int loc, byte[] raw, String reason) implements Blocks {
        @Override public long seq() { return -1; }
    }
}
