package lab.ext4.jbd2;

import java.util.List;

/** Domain model for parsed ext4/JBD2 journal images. */
public final class Model {
    private Model() {}

    public static final long MAGIC = 0xC03B3998L;
    public static final int TYPE_DESCRIPTOR = 1;
    public static final int TYPE_COMMIT = 2;
    public static final int TYPE_SUPER_V1 = 3;
    public static final int TYPE_SUPER_V2 = 4;
    public static final int TYPE_REVOKE = 5;
    public static final int FLAG_LAST_TAG = 8;

    /**
     * A domain quantity that keeps its original textual representation, unit and
     * coordinate basis. Normalization happens only at the derivation layer; when a
     * value cannot be converted it stays in place with a diagnostic.
     */
    public record Quantity(String raw, Long value, String unit, String basis, String diagnostic) {
        public static Quantity of(long value, String unit, String basis) {
            return new Quantity(Long.toString(value), value, unit, basis, null);
        }
        public static Quantity invalid(String raw, String unit, String basis, String diagnostic) {
            return new Quantity(raw, null, unit, basis, diagnostic);
        }
    }

    public record Tag(long fsBlock, int flags) {
        public boolean last() { return (flags & FLAG_LAST_TAG) != 0; }
    }

    public sealed interface JournalBlock {
        int index();
        byte[] raw();
        String id();
    }

    public record Descriptor(int index, long seq, List<Tag> tags, boolean checksumOk, byte[] raw)
            implements JournalBlock {
        public String id() { return "blk-" + index; }
    }

    public record Data(int index, long fsBlock, byte[] raw) implements JournalBlock {
        public String id() { return "blk-" + index; }
    }

    public record Commit(int index, long seq, boolean checksumOk, byte[] raw) implements JournalBlock {
        public String id() { return "blk-" + index; }
    }

    public record Revoke(int index, long seq, List<Long> fsBlocks, byte[] raw) implements JournalBlock {
        public String id() { return "blk-" + index; }
    }

    public record Unknown(int index, String reason, byte[] raw) implements JournalBlock {
        public String id() { return "blk-" + index; }
    }

    public record Superblock(
            Quantity blockSize,
            long maxlen,
            long first,
            long sequence,
            long start,
            String checksumType,
            boolean valid,
            List<String> diagnostics,
            byte[] raw) {}

    /** A transaction group assembled during the scan phase. */
    public static final class Txn {
        public final long seq;
        public final List<Descriptor> descriptors = new java.util.ArrayList<>();
        public final List<Data> data = new java.util.ArrayList<>();
        public final List<Revoke> revokes = new java.util.ArrayList<>();
        public Commit commit;
        public Status status = Status.OPEN;

        public Txn(long seq) { this.seq = seq; }

        public String id() { return "txn-" + Seq.hex(seq); }

        public enum Status { OPEN, COMMITTED, HALF, STALE, UNCERTAIN }
    }

    /** Why the scan stopped advancing. */
    public record Boundary(Reason reason, int journalIndex, String detail) {
        public enum Reason { RING_EXHAUSTED, HALF_TRANSACTION, CHECKSUM_FAILURE, INVALID_SUPERBLOCK, STRAY_BLOCK, CRASH_POINT }
    }

    public record JournalImage(String sourceId, Superblock superblock, List<byte[]> blocks) {}
}
