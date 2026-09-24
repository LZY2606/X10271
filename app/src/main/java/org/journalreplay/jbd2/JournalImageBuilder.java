package org.journalreplay.jbd2;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds synthetic but format-faithful JBD2 journal images for fixtures and
 * tests. Descriptor tag checksums and commit checksums are computed exactly as
 * {@link ReplayEngine} verifies them, so images pass verification unless a
 * corruption hook is used.
 */
public final class JournalImageBuilder {
    private final int blockSize;
    private final ChecksumVersion version;
    private final byte[] uuid;
    private final long uuidCrc;
    private final List<byte[]> blocks = new ArrayList<>();

    // pending transaction state
    private Integer pendingDesc = null;     // index of descriptor block
    private List<Long> pendingTags = null;
    private final List<Integer> pendingData = new ArrayList<>();

    public JournalImageBuilder(int blockSize, ChecksumVersion version, byte[] uuid) {
        this.blockSize = blockSize;
        this.version = version;
        this.uuid = uuid;
        this.uuidCrc = Checksums.crc32c(uuid, 0, uuid.length);
    }

    private byte[] newBlock() {
        byte[] b = new byte[blockSize];
        blocks.add(b);
        return b;
    }

    private void header(byte[] b, BlockType type, long seq) {
        JournalParser.put32(b, 0, JournalParser.MAGIC);
        JournalParser.put32(b, 4, type.code);
        JournalParser.put32(b, 8, seq);
    }

    public JournalImageBuilder superblockV2(long firstSeq, long maxLen, long first) {
        byte[] b = newBlock();
        header(b, BlockType.SUPERBLOCK_V2, 0);
        JournalParser.put32(b, 12, blockSize);
        JournalParser.put32(b, 16, maxLen);
        JournalParser.put32(b, 20, first);
        JournalParser.put32(b, 24, firstSeq);
        JournalParser.put32(b, 28, 1); // s_start
        System.arraycopy(uuid, 0, b, 32, 16);
        JournalParser.put32(b, 48, version.code);
        return this;
    }

    public JournalImageBuilder descriptor(long seq, long... fsBlocks) {
        flushPendingWithoutCommit();
        byte[] b = newBlock();
        header(b, BlockType.DESCRIPTOR, seq);
        pendingDesc = blocks.size() - 1;
        pendingTags = new ArrayList<>();
        for (long f : fsBlocks) pendingTags.add(f);
        pendingData.clear();
        return this;
    }

    public JournalImageBuilder data(String fill) {
        byte[] b = newBlock();
        byte[] f = fill.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < b.length; i++) b[i] = f[i % f.length];
        pendingData.add(blocks.size() - 1);
        return this;
    }

    public JournalImageBuilder revoke(long seq, long... fsBlocks) {
        byte[] b = newBlock();
        header(b, BlockType.REVOKE, seq);
        JournalParser.put32(b, 12, fsBlocks.length);
        for (int i = 0; i < fsBlocks.length; i++) {
            JournalParser.put32(b, 16 + i * 4, fsBlocks[i]);
        }
        return this;
    }

    /** Writes a commit block with a correct checksum for the open transaction. */
    public JournalImageBuilder commit(long seq) {
        return commitInternal(seq, false);
    }

    /** Writes a commit block whose checksum does not match the transaction. */
    public JournalImageBuilder commitCorrupted(long seq) {
        return commitInternal(seq, true);
    }

    private JournalImageBuilder commitInternal(long seq, boolean corrupt) {
        if (pendingDesc == null) throw new IllegalStateException("no open transaction");
        finalizeDescriptorTags();
        byte[] content = txContent();
        byte[] b = newBlock();
        header(b, BlockType.COMMIT, seq);
        b[12] = (byte) version.code;
        b[13] = 4;
        long sum = switch (version) {
            case V1_CRC16 -> Checksums.crc16(content, 0, content.length);
            case V2_CRC32C -> Checksums.crc32c(0, content, 0, content.length);
            case V3_CRC32C_PER_TAG -> Checksums.crc32c(uuidCrc, content, 0, content.length);
        };
        if (corrupt) sum ^= 0xDEADL;
        JournalParser.put32(b, 16, sum);
        pendingDesc = null;
        pendingTags = null;
        pendingData.clear();
        return this;
    }

    /** Leaves the open transaction unfinished (half transaction at image end). */
    public JournalImageBuilder flushPendingWithoutCommit() {
        if (pendingDesc != null) {
            finalizeDescriptorTags();
            pendingDesc = null;
            pendingTags = null;
            pendingData.clear();
        }
        return this;
    }

    private void finalizeDescriptorTags() {
        byte[] desc = blocks.get(pendingDesc);
        for (int i = 0; i < pendingTags.size(); i++) {
            int p = 12 + i * 16;
            JournalParser.put32(desc, p, pendingTags.get(i));
            int flags = (i == pendingTags.size() - 1) ? 1 : 0;
            desc[p + 8] = (byte) (flags >>> 8);
            desc[p + 9] = (byte) flags;
            desc[p + 10] = 0;
            desc[p + 11] = 16;
            if (version == ChecksumVersion.V3_CRC32C_PER_TAG && i < pendingData.size()) {
                byte[] fsBe = new byte[4];
                JournalParser.put32(fsBe, 0, pendingTags.get(i));
                long seed = Checksums.crc32c(uuidCrc, fsBe, 0, 4);
                byte[] d = blocks.get(pendingData.get(i));
                JournalParser.put32(desc, p + 4, Checksums.crc32c(seed, d, 0, d.length));
            }
        }
    }

    private byte[] txContent() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(blocks.get(pendingDesc), 0, blockSize);
        for (int idx : pendingData) out.write(blocks.get(idx), 0, blockSize);
        return out.toByteArray();
    }

    public byte[] build() {
        flushPendingWithoutCommit();
        byte[] image = new byte[blocks.size() * blockSize];
        for (int i = 0; i < blocks.size(); i++) {
            System.arraycopy(blocks.get(i), 0, image, i * blockSize, blockSize);
        }
        return image;
    }
}
