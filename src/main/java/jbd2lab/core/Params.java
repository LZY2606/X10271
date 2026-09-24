package jbd2lab.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Import parameters. Every value keeps the original representation and unit basis; the
 * block size is derived from the ext4 superblock (1024 << s_log_block_size) but an
 * explicitly supplied value overrides it. An unconvertible block size is retained with a
 * diagnostic and blocks replay (never silently clamped).
 */
public final class Params {
    public byte[] ext4Superblock;            // raw 1024-byte ext4 sb (or null)
    public byte[] journalSuperblock;         // raw jbd2 superblock v2 block (or null)
    public Quant blockSize;                  // bytes; may be invalid
    public Quant ringStart;                  // journal_blocks, 0-based from ring start
    public Quant crashPoint;                 // journal_blocks: scan/replay cutoff slot (relative)
    public int checksumType = JBD2.CHKSUM_CRC32C;
    public String checksumTypeRaw = "crc32c";
    public String blockSizeSource = "explicit";

    /** Derive the block-size Quant from ext4 superblock bytes, preserving raw text. */
    public static Quant blockSizeFromExt4Sb(byte[] sb) {
        if (sb == null || sb.length < 28) {
            return Quant.invalid("missing", "ext4 superblock absent or truncated", Quant.UNIT_BYTES);
        }
        int log = sb[24] & 0xFF;
        int low = (sb[26] & 0xFF) | ((sb[27] & 0xFF) << 8);
        String raw = "1024 << " + log + " (s_log_block_size raw=0x"
                + Integer.toHexString(log) + ", magic=0x" + Integer.toHexString(low) + ")";
        if (log > 6) {
            return Quant.invalid(raw, "s_log_block_size=" + log + " yields an unsupported block size",
                    Quant.UNIT_BYTES);
        }
        long size = 1024L << log;
        if (!validBlockSize(size)) {
            return Quant.invalid(raw, "block size " + size + " is not a power of two in [512,65536]",
                    Quant.UNIT_BYTES);
        }
        return Quant.of(size, Quant.UNIT_BYTES, raw);
    }

    public static Quant parseBlockSize(String raw) {
        String t = raw.trim().toLowerCase();
        try {
            long v;
            if (t.startsWith("0x")) v = Long.parseLong(t.substring(2), 16);
            else v = Long.parseLong(t);
            if (!validBlockSize(v)) {
                return Quant.invalid(raw, "block size " + v + " is not a power of two in [512,65536]",
                        Quant.UNIT_BYTES);
            }
            return Quant.of(v, Quant.UNIT_BYTES, raw);
        } catch (NumberFormatException e) {
            return Quant.invalid(raw, "cannot interpret block size: " + e.getMessage(), Quant.UNIT_BYTES);
        }
    }

    public static boolean validBlockSize(long v) {
        return v >= 512 && v <= 65536 && Long.bitCount(v) == 1;
    }

    public static int parseChecksumType(String raw) {
        switch (raw.trim().toLowerCase()) {
            case "none": case "0": return JBD2.CHKSUM_NONE;
            case "crc32": case "1": return JBD2.CHKSUM_CRC32;
            case "crc32c": case "2": return JBD2.CHKSUM_CRC32C;
            default: return -1;
        }
    }

    public Map<String, Quant> coordinates() {
        Map<String, Quant> m = new LinkedHashMap<>();
        m.put("block_size", blockSize);
        m.put("ring_start", ringStart);
        m.put("crash_point", crashPoint);
        return m;
    }
}
