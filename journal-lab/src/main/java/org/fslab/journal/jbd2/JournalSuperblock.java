package org.fslab.journal.jbd2;

/**
 * Parsed journal superblock (JBD2 v2 form), the authoritative description of the
 * ring geometry, checksum version and revoke support.
 *
 * <p>Field offsets follow the JBD2 {@code journal_superblock_s} layout; this lab
 * uses the fields needed for replay and stores the remaining bytes untouched.
 */
public record JournalSuperblock(
        int blockSize,
        int totalBlocks,
        long firstCommitSeq,
        long startBlock,
        int checksumVersion,
        int featureCompat,
        int featureIncompat,
        byte[] uuid,
        byte[] raw) {

    public static final int OFF_BLOCKSIZE = 12;       // s_blocksize
    public static final int OFF_TOTAL = 16;           // s_maxlen
    public static final int OFF_FIRST_SEQ = 20;       // s_sequence
    public static final int OFF_START = 24;           // s_start
    public static final int OFF_FEATURE_COMPAT = 236;
    public static final int OFF_FEATURE_INCOMPAT = 240;
    public static final int OFF_CHECKSUM_TYPE = 252;  // s_checksum_type
    public static final int OFF_UUID = 190;           // s_uuid (16 bytes)

    public static JournalSuperblock parse(byte[] block) {
        int magic = (int) Jbd2.get32(block, 0);
        int type = (int) Jbd2.get32(block, 4);
        if (magic != Jbd2.HEADER_MAGIC
                || (type != Jbd2.TYPE_SUPERBLOCK_V1 && type != Jbd2.TYPE_SUPERBLOCK_V2)) {
            throw new IllegalArgumentException("block 0 is not a journal superblock");
        }
        int blockSize = (int) Jbd2.get32(block, OFF_BLOCKSIZE);
        int total = (int) Jbd2.get32(block, OFF_TOTAL);
        long seq = Jbd2.get32(block, OFF_FIRST_SEQ);
        long start = Jbd2.get32(block, OFF_START);
        int compat = (int) Jbd2.get32(block, OFF_FEATURE_COMPAT);
        int incompat = (int) Jbd2.get32(block, OFF_FEATURE_INCOMPAT);
        int checksumType = Jbd2.get8(block, OFF_CHECKSUM_TYPE);
        byte[] uuid = new byte[16];
        System.arraycopy(block, OFF_UUID, uuid, 0, 16);
        return new JournalSuperblock(blockSize, total, seq, start,
                checksumType, compat, incompat, uuid, block.clone());
    }

    public static byte[] write(int blockSize, int totalBlocks, long firstCommitSeq,
                               long startBlock, int checksumVersion, int incompatFlags) {
        byte[] block = new byte[Jbd2.SUPERBLOCK_SIZE];
        Jbd2.put32(block, 0, Jbd2.SUPERBLOCK_MAGIC);
        Jbd2.put32(block, 4, checksumVersion == Jbd2.CHECKSUM_V0_NONE
                ? Jbd2.TYPE_SUPERBLOCK_V1 : Jbd2.TYPE_SUPERBLOCK_V2);
        Jbd2.put32(block, OFF_BLOCKSIZE, blockSize);
        Jbd2.put32(block, OFF_TOTAL, totalBlocks);
        Jbd2.put32(block, OFF_FIRST_SEQ, firstCommitSeq);
        Jbd2.put32(block, OFF_START, startBlock);
        Jbd2.put32(block, 60, 0); // errno
        Jbd2.put32(block, OFF_FEATURE_COMPAT,
                checksumVersion == Jbd2.CHECKSUM_V0_NONE ? 0 : Jbd2.FEATURE_COMPAT_CHECKSUM);
        int incompat = Jbd2.FEATURE_INCOMPAT_REVOKE | incompatFlags;
        if (checksumVersion == Jbd2.CHECKSUM_V3_BLOCK) {
            incompat |= Jbd2.FEATURE_INCOMPAT_CHECKSUM_V3;
        }
        Jbd2.put32(block, OFF_FEATURE_INCOMPAT, incompat);
        Jbd2.putUuid(block, OFF_UUID, Jbd2.asciiUuid("3f2a7c11-4b52-4d9a-8e21-a1b2c3d4e5f6"));
        Jbd2.put8(block, OFF_CHECKSUM_TYPE, checksumVersion);
        return block;
    }

    public int dataRingSize() {
        return totalBlocks - 1;
    }
}
