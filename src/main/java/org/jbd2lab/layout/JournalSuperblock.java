package org.jbd2lab.layout;

import org.jbd2lab.util.Be;

/** Parsed journal superblock v2 (first journal block). */
public record JournalSuperblock(
        long magic,
        int blocktype,
        int blockSize,
        int compatFeatures,
        int incompatFeatures,
        int roCompatFeatures,
        long journalUuidLow,
        long journalFirst,
        long startSeq,
        long journalNumUsers,
        long journalMaxLen,
        int checksumType,
        int tailChecksum,
        byte[] raw) {

    public int checksumVersion() {
        return Jbd2.checksumVersion(incompatFeatures);
    }

    public boolean has64Bit() {
        return (incompatFeatures & Jbd2.INCOMPAT_FEATURE_64BLOCK) != 0;
    }

    public static JournalSuperblock parse(byte[] b) {
        return new JournalSuperblock(
                Be.u32(b, 0),
                (int) Be.u32(b, 4),
                Be.u16(b, 8),
                Be.u16(b, 10),
                Be.u32(b, 12),
                Be.u32(b, 16),
                Be.u32(b, 20),
                Be.u32(b, 24),
                Be.u32(b, 28),
                Be.u32(b, 32),
                Be.u32(b, 36),
                b[40] & 0xFF,
                (int) Be.u32(b, Jbd2.SB_SIZE - 4),
                b.clone());
    }
}
