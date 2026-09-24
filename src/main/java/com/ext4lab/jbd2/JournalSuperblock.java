package com.ext4lab.jbd2;

import java.util.ArrayList;
import java.util.List;

/** Parsed journal superblock (v2). Raw bytes are always preserved alongside. */
public final class JournalSuperblock {
    public final byte[] raw;
    public final long sequence;       // s_sequence: first transaction id expected
    public final Quantity blockSize;  // s_blocksize, unit "byte", basis "fs-block"
    public final long maxlen;         // s_maxlen: journal length in blocks
    public final long first;          // s_first: first journal block (ring origin)
    public final long start;          // s_start: block index of oldest transaction, 0 = empty
    public final int checksumType;    // s_checksum_type
    public final int checksumVersion; // derived: v1/v2/v3, preserved verbatim
    public final boolean superblockChecksumOk;
    public final List<String> diagnostics = new ArrayList<>();

    public JournalSuperblock(byte[] raw, long sequence, Quantity blockSize, long maxlen,
                             long first, long start, int checksumType, int checksumVersion,
                             boolean superblockChecksumOk) {
        this.raw = raw;
        this.sequence = sequence;
        this.blockSize = blockSize;
        this.maxlen = maxlen;
        this.first = first;
        this.start = start;
        this.checksumType = checksumType;
        this.checksumVersion = checksumVersion;
        this.superblockChecksumOk = superblockChecksumOk;
    }

    public static JournalSuperblock parse(byte[] raw) {
        if (raw.length < 1024) throw new IllegalArgumentException("superblock too small: " + raw.length);
        long magic = JournalFormat.u32(raw, 0);
        if (magic != JournalFormat.MAGIC) throw new IllegalArgumentException("bad magic: " + Long.toHexString(magic));
        long btype = JournalFormat.u32(raw, 4);
        if (btype != JournalFormat.BT_SUPERBLOCK_V2 && btype != JournalFormat.BT_SUPERBLOCK_V1)
            throw new IllegalArgumentException("not a superblock: type=" + btype);
        long bsRaw = JournalFormat.u32(raw, 12);
        Quantity blockSize;
        List<String> diags = new ArrayList<>();
        if (JournalFormat.validBlockSize((int) bsRaw)) {
            blockSize = Quantity.of(bsRaw, "byte", "fs-block");
        } else {
            blockSize = Quantity.invalid(Long.toString(bsRaw), "byte", "fs-block",
                    "invalid-block-size: value kept raw, offsets not convertible");
        }
        long maxlen = JournalFormat.u32(raw, 16);
        long first = JournalFormat.u32(raw, 20);
        long sequence = JournalFormat.u32(raw, 24);
        long start = JournalFormat.u32(raw, 28);
        int checksumType = raw.length > 88 ? (raw[88] & 0xFF) : 0;
        long incompat = JournalFormat.u32(raw, 36);
        int version;
        if ((incompat & 0x2) != 0) version = Crc.V3_CRC32C;       // CHECKSUM_V3
        else if ((incompat & 0x1) != 0) version = Crc.V2_CRC32;   // CHECKSUM (v2)
        else version = Crc.V1_NONE;
        boolean sbOk = true;
        if (version == Crc.V3_CRC32C && raw.length >= 1024) {
            long stored = JournalFormat.u32(raw, 1020);
            long calc = Crc.compute(Crc.V3_CRC32C, raw, 0, 1020);
            sbOk = stored == calc;
        }
        JournalSuperblock sb = new JournalSuperblock(raw, sequence, blockSize, maxlen, first,
                start, checksumType, version, sbOk);
        sb.diagnostics.addAll(diags);
        if (blockSize.diagnostic != null) sb.diagnostics.add(blockSize.diagnostic);
        if (!sbOk) sb.diagnostics.add("superblock-checksum-mismatch");
        return sb;
    }
}
