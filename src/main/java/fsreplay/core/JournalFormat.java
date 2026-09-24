package fsreplay.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** JBD2-style on-disk layout (big-endian). Shared by parser and fixture builder. */
public final class JournalFormat {
    private JournalFormat() {}

    public static final long MAGIC = 0xC03B3998L;
    public static final int HEADER_SIZE = 12;
    public static final int TAG_SIZE = 16;

    public static final int TYPE_DESCRIPTOR = 1;
    public static final int TYPE_COMMIT = 2;
    public static final int TYPE_SUPER_V1 = 3;
    public static final int TYPE_SUPER_V2 = 4;
    public static final int TYPE_REVOKE = 5;

    public static final int FLAG_ESCAPE = 1;
    public static final int FLAG_SAME_UUID = 2;
    public static final int FLAG_DELETED = 4;
    public static final int FLAG_LAST = 8;

    public static final int FEAT_INCOMPAT_CSUM_V3 = 0x1;

    public static void put32(byte[] buf, int off, long v) {
        buf[off] = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
    }

    public static long get32(byte[] buf, int off) {
        return ((buf[off] & 0xFFL) << 24) | ((buf[off + 1] & 0xFFL) << 16)
                | ((buf[off + 2] & 0xFFL) << 8) | (buf[off + 3] & 0xFFL);
    }

    public static void put16(byte[] buf, int off, int v) {
        buf[off] = (byte) (v >>> 8);
        buf[off + 1] = (byte) v;
    }

    public static int get16(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
    }

    public static void header(byte[] buf, int off, int blockType, long sequence) {
        put32(buf, 0, MAGIC);
        put32(buf, 4, blockType);
        put32(buf, 8, sequence);
    }

    /** Deterministic builder for fixtures and tests. */
    public static final class Builder {
        private final int blockSize;
        private final int ringBlocks;
        private final Checksum checksum;
        private final List<byte[]> blocks = new ArrayList<>();
        private long superSequence;
        private int startBlock = 1;
        private boolean corruptNextCommit;

        public Builder(int blockSize, int ringBlocks, Checksum checksum) {
            this.blockSize = blockSize;
            this.ringBlocks = ringBlocks;
            this.checksum = checksum;
        }

        public Builder superSequence(long seq) { this.superSequence = seq; return this; }
        public Builder startBlock(int b) { this.startBlock = b; return this; }
        /** Next commit block written gets a deliberately wrong checksum (checksum break). */
        public Builder corruptNextCommit() { this.corruptNextCommit = true; return this; }

        private byte[] blank() { return new byte[blockSize]; }

        /** Append a full transaction: descriptor + data blocks + commit. Returns this. */
        public Builder txn(long sequence, long... fsBlocks) {
            List<byte[]> dataBlocks = new ArrayList<>();
            for (int i = 0; i < fsBlocks.length; i++) {
                byte[] d = blank();
                String tag = "DATA seq=" + Seq.hex(sequence) + " fs=" + fsBlocks[i] + " #" + i;
                byte[] t = tag.getBytes();
                System.arraycopy(t, 0, d, 0, t.length);
                dataBlocks.add(d);
            }
            byte[] desc = blank();
            header(desc, 0, TYPE_DESCRIPTOR, sequence);
            for (int i = 0; i < fsBlocks.length; i++) {
                int off = HEADER_SIZE + i * TAG_SIZE;
                put32(desc, off, fsBlocks[i]);
                long tagCrc = checksum.compute(dataBlocks.get(i), 0, blockSize, sequence);
                put32(desc, off + 4, tagCrc);
                int flags = FLAG_SAME_UUID | (i == fsBlocks.length - 1 ? FLAG_LAST : 0);
                put16(desc, off + 8, flags);
            }
            byte[] commit = blank();
            header(commit, 0, TYPE_COMMIT, sequence);
            long crc = txnChecksum(sequence, desc, dataBlocks, commit);
            if (corruptNextCommit) {
                crc ^= 0xDEADBEEFL;
                corruptNextCommit = false;
            }
            put32(commit, HEADER_SIZE, crc);
            blocks.add(desc);
            blocks.addAll(dataBlocks);
            blocks.add(commit);
            return this;
        }

        /** Append descriptor + data with NO commit (half transaction). */
        public Builder halfTxn(long sequence, long... fsBlocks) {
            List<byte[]> dataBlocks = new ArrayList<>();
            for (int i = 0; i < fsBlocks.length; i++) {
                byte[] d = blank();
                String tag = "DATA seq=" + Seq.hex(sequence) + " fs=" + fsBlocks[i] + " half";
                byte[] t = tag.getBytes();
                System.arraycopy(t, 0, d, 0, t.length);
                dataBlocks.add(d);
            }
            byte[] desc = blank();
            header(desc, 0, TYPE_DESCRIPTOR, sequence);
            for (int i = 0; i < fsBlocks.length; i++) {
                int off = HEADER_SIZE + i * TAG_SIZE;
                put32(desc, off, fsBlocks[i]);
                put32(desc, off + 4, checksum.compute(dataBlocks.get(i), 0, blockSize, sequence));
                put16(desc, off + 8, FLAG_SAME_UUID | (i == fsBlocks.length - 1 ? FLAG_LAST : 0));
            }
            blocks.add(desc);
            blocks.addAll(dataBlocks);
            return this;
        }

        /** Append a revoke block (its own transaction). */
        public Builder revoke(long sequence, long... fsBlocks) {
            byte[] r = blank();
            header(r, 0, TYPE_REVOKE, sequence);
            put32(r, HEADER_SIZE, fsBlocks.length * 4L);
            for (int i = 0; i < fsBlocks.length; i++) {
                put32(r, HEADER_SIZE + 4 + i * 4, fsBlocks[i]);
            }
            blocks.add(r);
            return this;
        }

        private long txnChecksum(long sequence, byte[] desc, List<byte[]> dataBlocks, byte[] commit) {
            java.util.zip.Checksum algo = checksum == Checksum.V3 ? new java.util.zip.CRC32C() : new java.util.zip.CRC32();
            algo.update((int) sequence);
            algo.update(desc, 0, blockSize);
            for (byte[] d : dataBlocks) algo.update(d, 0, blockSize);
            algo.update(commit, 0, HEADER_SIZE);
            return algo.getValue() & 0xFFFFFFFFL;
        }

        /** Commit checksum verification rule, mirrored by the parser. */
        public long commitChecksumOf(long sequence, byte[] desc, List<byte[]> dataBlocks, byte[] commitHeader) {
            return txnChecksum(sequence, desc, dataBlocks, commitHeader);
        }

        /** Build the journal image: block 0 = journal superblock, ring blocks after. */
        public byte[] build() {
            int total = ringBlocks;
            byte[] img = new byte[total * blockSize];
            byte[] sb = blank();
            put32(sb, 0, MAGIC);
            put32(sb, 4, TYPE_SUPER_V2);
            put32(sb, 8, 0);
            put32(sb, 12, blockSize);
            put32(sb, 16, total);
            put32(sb, 20, 1);
            put32(sb, 24, superSequence);
            put32(sb, 28, startBlock);
            put32(sb, 32, 0);
            put32(sb, 36, checksum == Checksum.V3 ? FEAT_INCOMPAT_CSUM_V3 : 0);
            System.arraycopy(sb, 0, img, 0, blockSize);
            int idx = startBlock;
            for (byte[] b : blocks) {
                System.arraycopy(b, 0, img, idx * blockSize, blockSize);
                idx = (idx + 1) % total;
                if (idx == 0) idx = 1; // never overwrite journal superblock
            }
            return img;
        }
    }
}
