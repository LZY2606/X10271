package lab.ext4.jbd2;

import lab.ext4.jbd2.Model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/** Parses raw journal bytes into typed JBD2 blocks. Original bytes are always retained. */
public final class JournalParser {

    public static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    public static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static Checksum checksumFor(String checksumType) {
        return "v2".equals(checksumType) ? new CRC32() : new CRC32C();
    }

    public static long crc(String checksumType, byte[] data, int off, int len) {
        Checksum c = checksumFor(checksumType);
        c.update(data, off, len);
        return c.getValue() & 0xFFFF_FFFFL;
    }

    public static Superblock parseSuperblock(byte[] raw) {
        List<String> diagnostics = new ArrayList<>();
        long magic = u32(raw, 0);
        int type = (int) u32(raw, 4);
        if (magic != Model.MAGIC || (type != Model.TYPE_SUPER_V1 && type != Model.TYPE_SUPER_V2)) {
            diagnostics.add("superblock magic/type mismatch: magic=" + Long.toHexString(magic) + " type=" + type);
        }
        long blockSizeRaw = u32(raw, 12);
        Quantity blockSize;
        if (blockSizeRaw >= 1024 && blockSizeRaw <= 65536 && (blockSizeRaw & (blockSizeRaw - 1)) == 0) {
            blockSize = Quantity.of(blockSizeRaw, "bytes", "journal-block-size");
        } else {
            // Invalid block size: keep the original value in place, attach a diagnostic.
            blockSize = Quantity.invalid(Long.toString(blockSizeRaw), "bytes", "journal-block-size",
                    "block size " + blockSizeRaw + " is not a power of two within [1024, 65536]");
            diagnostics.add(blockSize.diagnostic());
        }
        long maxlen = u32(raw, 16);
        long first = u32(raw, 20);
        long sequence = u32(raw, 24);
        long start = u32(raw, 28);
        String checksumType = type == Model.TYPE_SUPER_V2 && raw.length > 36 && raw[36] != 0 ? "v3" : "v2";
        boolean valid = diagnostics.isEmpty();
        return new Superblock(blockSize, maxlen, first, sequence, start, checksumType, valid, diagnostics, raw);
    }

    /** Parse one ring block at {@code index}; never throws, unknown content becomes {@link Unknown}. */
    public static JournalBlock parseBlock(int index, byte[] raw, String checksumType) {
        if (raw.length < 12) {
            return new Unknown(index, "short block (" + raw.length + " bytes)", raw);
        }
        long magic = u32(raw, 0);
        if (magic != Model.MAGIC) {
            return new Unknown(index, "bad magic " + Long.toHexString(magic), raw);
        }
        int type = (int) u32(raw, 4);
        long seq = u32(raw, 8);
        switch (type) {
            case Model.TYPE_DESCRIPTOR -> {
                List<Tag> tags = new ArrayList<>();
                int off = 12;
                int tagEnd = raw.length - 4; // trailing checksum
                while (off + 16 <= tagEnd) {
                    long fsBlock = u32(raw, off);
                    int flags = (int) u32(raw, off + 4);
                    Tag t = new Tag(fsBlock, flags);
                    tags.add(t);
                    off += 16;
                    if (t.last()) break;
                }
                long stored = u32(raw, raw.length - 4);
                long computed = crc(checksumType, raw, 0, raw.length - 4);
                return new Descriptor(index, seq, tags, stored == computed, raw);
            }
            case Model.TYPE_COMMIT -> {
                long stored = u32(raw, 12);
                byte[] zeroed = raw.clone();
                putU32(zeroed, 12, 0);
                long computed = crc(checksumType, zeroed, 0, zeroed.length);
                return new Commit(index, seq, stored == computed, raw);
            }
            case Model.TYPE_REVOKE -> {
                long count = u32(raw, 12);
                List<Long> blocks = new ArrayList<>();
                int off = 16;
                for (long i = 0; i < count && off + 4 <= raw.length; i++, off += 4) {
                    blocks.add(u32(raw, off));
                }
                return new Revoke(index, seq, blocks, raw);
            }
            default -> {
                return new Unknown(index, "unhandled block type " + type, raw);
            }
        }
    }

    /** A data block carries no header; it is identified positionally after a descriptor. */
    public static Data dataBlock(int index, long fsBlock, byte[] raw) {
        return new Data(index, fsBlock, raw);
    }
}
