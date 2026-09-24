package com.journalreplay.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Parsed JBD2 journal superblock (v2 layout; v1 lacks the feature fields).
 * Raw bytes are always retained alongside the parsed view.
 */
public record Superblock(
        byte[] raw,
        long blockSize,
        long maxlen,
        long first,
        long sequence,
        long start,
        long featureCompat,
        long featureIncompat,
        long featureRoCompat,
        byte[] uuid,
        String checksumVersion) {

    public static final int OFF_BLOCKSIZE = 0x0C;
    public static final int OFF_MAXLEN = 0x10;
    public static final int OFF_FIRST = 0x14;
    public static final int OFF_SEQUENCE = 0x18;
    public static final int OFF_START = 0x1C;
    public static final int OFF_FEATURE_COMPAT = 0x24;
    public static final int OFF_FEATURE_INCOMPAT = 0x28;
    public static final int OFF_FEATURE_RO_COMPAT = 0x2C;
    public static final int OFF_UUID = 0x30;

    public static Superblock parse(byte[] raw) {
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        long magic = Integer.toUnsignedLong(b.getInt(0));
        if (magic != JournalFormat.MAGIC) {
            throw new IllegalArgumentException("journal superblock magic mismatch: 0x" + Long.toHexString(magic));
        }
        int type = b.getInt(4);
        if (type != JournalFormat.BLOCK_SUPER_V1 && type != JournalFormat.BLOCK_SUPER_V2) {
            throw new IllegalArgumentException("not a journal superblock, blocktype=" + type);
        }
        long blockSize = Integer.toUnsignedLong(b.getInt(OFF_BLOCKSIZE));
        long maxlen = Integer.toUnsignedLong(b.getInt(OFF_MAXLEN));
        long first = Integer.toUnsignedLong(b.getInt(OFF_FIRST));
        long sequence = Integer.toUnsignedLong(b.getInt(OFF_SEQUENCE));
        long start = Integer.toUnsignedLong(b.getInt(OFF_START));
        long fc = type == JournalFormat.BLOCK_SUPER_V2 ? Integer.toUnsignedLong(b.getInt(OFF_FEATURE_COMPAT)) : 0;
        long fi = type == JournalFormat.BLOCK_SUPER_V2 ? Integer.toUnsignedLong(b.getInt(OFF_FEATURE_INCOMPAT)) : 0;
        long fr = type == JournalFormat.BLOCK_SUPER_V2 ? Integer.toUnsignedLong(b.getInt(OFF_FEATURE_RO_COMPAT)) : 0;
        byte[] uuid = type == JournalFormat.BLOCK_SUPER_V2
                ? Arrays.copyOfRange(raw, OFF_UUID, OFF_UUID + 16) : new byte[16];
        String csumVersion;
        if ((fi & JournalFormat.FEATURE_INCOMPAT_CSUM_V3) != 0) csumVersion = "v3";
        else if ((fi & JournalFormat.FEATURE_INCOMPAT_CSUM_V2) != 0) csumVersion = "v2";
        else csumVersion = "v1-none";
        return new Superblock(raw.clone(), blockSize, maxlen, first, sequence, start, fc, fi, fr, uuid, csumVersion);
    }

    public boolean has64bit() {
        return (featureIncompat & JournalFormat.FEATURE_INCOMPAT_64BIT) != 0;
    }

    public boolean csumV3() {
        return "v3".equals(checksumVersion);
    }

    public boolean hasCsum() {
        return csumV3() || "v2".equals(checksumVersion);
    }

    public int tagSize() {
        return JournalFormat.tagSize(csumV3(), has64bit());
    }

    /** Valid ext4 journal block sizes are powers of two within [1024, 65536]. */
    public static boolean isValidBlockSize(long bs) {
        return bs >= 1024 && bs <= 65536 && (bs & (bs - 1)) == 0;
    }
}
