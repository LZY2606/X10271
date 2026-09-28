package org.fslab.jbd2.core;

import org.fslab.jbd2.model.Diagnostic;
import org.fslab.jbd2.model.JournalSuperblock;
import org.fslab.jbd2.model.Q;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Decodes the first {@value Jbd2Const#SUPERBLOCK_SIZE} bytes of the journal as
 * a JBD2 v2 superblock. Invalid quantities (e.g. an unsupported block size) are
 * kept in their original textual form with a diagnostic.
 *
 * Checksum bytes live at offset 0xFC (16 bytes, big-endian hex text); the
 * checksum covers the superblock with those bytes zeroed.
 */
public final class SuperblockCodec {
    private SuperblockCodec() {}

    public static final int VALID_BLOCK_SIZES[] = {1024, 2048, 4096, 8192};

    public static JournalSuperblock decode(int physicalBlock, byte[] raw) {
        List<Diagnostic> diags = new ArrayList<>();
        Buffer buf = new Buffer(raw);
        long magic = buf.uintAt(0);
        int blocktype = buf.intAt(4);
        String blockSizeText = buf.asciiAt(8, 4);
        Q blockSize = parseBlockSize(blockSizeText, diags);
        Q total = parsePositive(buf.asciiAt(12, 4), "total journal blocks",
                "blocks (physical)", "j_blockslen", diags);
        Q first = parsePositive(buf.asciiAt(16, 4), "ring start (first block)",
                "blocks (physical, 0-based inside journal)", "s_first", diags);
        Q startSeq = parsePositive(buf.asciiAt(20, 4), "start sequence",
                "transaction sequence (u32 counter)", "h_sequence", diags);
        long compat = buf.uintAt(24);
        long incompat = buf.uintAt(28);
        long roCompat = buf.uintAt(32);
        String uuid = buf.hexAt(64, 16);
        long featuresIncompat = buf.uintAt(220);
        int checksumType = (int) (buf.uintAt(248) & 0xFFFFFFFFL);
        String checksumHex = buf.hexAt(0xFC, 16);
        boolean zeroed = isZeroed(raw, 0xFC, 16);
        Boolean ok = null;
        if (!zeroed) {
            long actual = checksumType == 1
                    ? crc32Zeroed(raw)
                    : Crc32c.of(zeroCopy(raw));
            long expected;
            try {
                expected = Long.parseUnsignedLong(checksumHex.trim(), 16);
            } catch (NumberFormatException ex) {
                expected = -1L;
            }
            ok = actual == expected;
            if (!ok) {
                diags.add(Diagnostic.error("SB_CHECKSUM", "journal-superblock@" + physicalBlock,
                        "superblock checksum mismatch under checksum type " + checksumType,
                        checksumHex));
            }
        }
        if (magic != Jbd2Const.SUPERBLOCK_V2_MAGIC) {
            diags.add(Diagnostic.error("SB_MAGIC", "journal-superblock@" + physicalBlock,
                    "bad JBD2 superblock magic", String.format("0x%08x", magic)));
        }
        if (checksumType != 1 && checksumType != 2) {
            diags.add(Diagnostic.warn("SB_CSTYPE", "journal-superblock@" + physicalBlock,
                    "unknown checksum type; version preserved, verification skipped",
                    Integer.toString(checksumType)));
        }
        if ((featuresIncompat & 0x1L) != 0 && checksumType == 0) {
            diags.add(Diagnostic.warn("SB_CSTYPE", "journal-superblock@" + physicalBlock,
                    "checksum feature asserted but checksum type is 0", "0"));
        }
        return new JournalSuperblock(physicalBlock, magic, blocktype, blockSize, total, first,
                startSeq, compat, incompat, roCompat, checksumType, uuid, checksumHex, ok, diags);
    }

    private static Q parseBlockSize(String text, List<Diagnostic> diags) {
        Q p = parsePositive(text, "block size", "bytes", "s_blocksize", diags);
        if (p.convertible()) {
            int v = (int) p.value().longValue();
            boolean powerOfTwo = v > 0 && (v & (v - 1)) == 0;
            boolean listed = false;
            for (int ok : VALID_BLOCK_SIZES) {
                if (ok == v) listed = true;
            }
            if (!powerOfTwo || !listed) {
                diags.add(Diagnostic.error("SB_BLOCKSIZE", "journal-superblock#s_blocksize",
                        "block size is not one of {1024,2048,4096,8192}; kept at source, "
                                + "derived scan cannot normalize",
                        text));
                return Q.unconvertible(text, "bytes", "s_blocksize",
                        "unsupported ext4 block size: " + text);
            }
        }
        return p;
    }

    private static Q parsePositive(String text, String name, String unit, String basis,
                                   List<Diagnostic> diags) {
        try {
            long v = Long.parseLong(text.trim());
            if (v < 0) {
                throw new NumberFormatException();
            }
            return Q.of(text, v, unit, basis);
        } catch (NumberFormatException ex) {
            diags.add(Diagnostic.error("SB_QUANTITY", "journal-superblock#" + basis,
                    name + " is not a non-negative integer; value kept at source", text));
            return Q.unconvertible(text, unit, basis, "not a non-negative integer: " + text);
        }
    }

    private static boolean isZeroed(byte[] raw, int off, int len) {
        for (int i = off; i < off + len; i++) {
            if (raw[i] != 0) return false;
        }
        return true;
    }

    private static byte[] zeroCopy(byte[] raw) {
        byte[] copy = raw.clone();
        for (int i = 0xFC; i < 0xFC + 16; i++) copy[i] = 0;
        return copy;
    }

    private static long crc32Zeroed(byte[] raw) {
        CRC32 crc = new CRC32();
        crc.update(zeroCopy(raw));
        return crc.getValue();
    }
}
