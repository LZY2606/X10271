package lab.ext4jbd2.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Encodes and parses JBD2 journal blocks for the lab.
 *
 * Layout (big-endian, little-endian? JBD2 is big-endian on disk) at fixed
 * block size B:
 *   common header  : magic 0xC03B3998 | blocktype | sequence   (12 bytes)
 *   descriptor (1) : tag records (12 bytes each, last has FLAG_LAST_TAG)
 *                    tail: crc32c over bytes [0,B-4) at [B-4,B)  (checksum v1)
 *   commit     (2) : crc32c over header at offset 12             (checksum v1)
 *   revoke     (3) : count(4) | count * fsBlock(4) | tail crc32c (checksum v1)
 *   superblock (5) : blocksize(4) maxlen(4) first(4) sequence(4) start(4) checksumType(4)
 */
public final class JournalCodec {
    private JournalCodec() {}

    private static ByteBuffer bb(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    }

    public static long crc32c(byte[] data) {
        // java.util.zip.CRC32C exists from Java 9; keep CRC32 here only as a
        // deterministic placeholder-free path by requesting CRC32C reflectively.
        try {
            CRC32 crc = (CRC32) Class.forName("java.util.zip.CRC32C")
                    .getDeclaredConstructor().newInstance();
            crc.update(data, 0, data.length);
            return crc.getValue() & 0xFFFFFFFFL;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("CRC32C unsupported", e);
        }
    }

    public static long crc32cPrefix(byte[] data, int len) {
        byte[] prefix = new byte[len];
        System.arraycopy(data, 0, prefix, 0, len);
        return crc32c(prefix);
    }

    private static byte[] blank(int blockSize) {
        return new byte[blockSize];
    }

    public static byte[] dataBlock(byte filler, int blockSize) {
        byte[] b = blank(blockSize);
        for (int i = 0; i < b.length; i++) b[i] = filler;
        b[0] = (byte) 0xDB; // data blocks have no journal magic
        return b;
    }

    public static byte[] dataBlockWithMarker(byte marker, int blockSize) {
        byte[] b = dataBlock((byte) 0, blockSize);
        b[0] = (byte) marker;
        b[1] = (byte) (marker >> 8);
        return b;
    }

    private static void writeHeader(byte[] block, int type, long sequence) {
        ByteBuffer b = bb(block);
        b.putInt(0, (int) Jbd2.MAGIC);
        b.putInt(4, type);
        b.putInt(8, (int) sequence);
    }

    public static byte[] superblock(int blockSize, long maxlen, long first, long sequence,
                                    long start, int checksumType) {
        byte[] block = blank(blockSize);
        writeHeader(block, Jbd2.TYPE_SUPERBLOCK_V2, 0);
        ByteBuffer b = bb(block);
        b.putInt(12, blockSize);
        b.putInt(16, (int) maxlen);
        b.putInt(20, (int) first);
        b.putInt(24, (int) sequence);
        b.putInt(28, (int) start);
        b.putInt(32, checksumType);
        return block;
    }

    public static byte[] descriptor(long sequence, List<Long> fsBlocks, int blockSize,
                                    int checksumType) {
        byte[] block = blank(blockSize);
        writeHeader(block, Jbd2.TYPE_DESCRIPTOR, sequence);
        ByteBuffer b = bb(block);
        for (int i = 0; i < fsBlocks.size(); i++) {
            int off = Jbd2.HEADER_LEN + i * Jbd2.TAG_LEN;
            int flags = (i == fsBlocks.size() - 1) ? Jbd2.FLAG_LAST_TAG : 0;
            b.putInt(off, fsBlocks.get(i).intValue());
            b.putInt(off + 4, flags);
            if (checksumType == Jbd2.CHECKSUM_CRC32C) {
                b.putInt(off + 8, 0); // tag crc reserved (lab keeps 0, block tail protects)
            }
        }
        if (checksumType == Jbd2.CHECKSUM_CRC32C) {
            b.putInt(blockSize - 4, (int) crc32cPrefix(block, blockSize - 4));
        }
        return block;
    }

    public static byte[] commit(long sequence, int blockSize, int checksumType) {
        byte[] block = blank(blockSize);
        writeHeader(block, Jbd2.TYPE_COMMIT, sequence);
        if (checksumType == Jbd2.CHECKSUM_CRC32C) {
            bb(block).putInt(12, (int) crc32cPrefix(block, 12));
        }
        return block;
    }

    public static byte[] revoke(long sequence, List<Long> fsBlocks, int blockSize,
                                int checksumType) {
        byte[] block = blank(blockSize);
        writeHeader(block, Jbd2.TYPE_REVOKE, sequence);
        ByteBuffer b = bb(block);
        b.putInt(12, fsBlocks.size());
        for (int i = 0; i < fsBlocks.size(); i++) {
            b.putInt(16 + i * 4, fsBlocks.get(i).intValue());
        }
        if (checksumType == Jbd2.CHECKSUM_CRC32C) {
            b.putInt(blockSize - 4, (int) crc32cPrefix(block, blockSize - 4));
        }
        return block;
    }

    /** Parse a journal block. checksumType 0 (v0) yields checksumOk=null. */
    public static JournalBlock parse(byte[] raw, int coordinate, int checksumType) {
        ByteBuffer b = bb(raw);
        long magic = b.getInt(0) & 0xFFFFFFFFL;
        if (magic != Jbd2.MAGIC) {
            return new JournalBlock(coordinate, -1, 0L, null, "bad magic", raw);
        }
        int type = b.getInt(4);
        long sequence = b.getInt(8) & 0xFFFFFFFFL;
        JournalBlock out = new JournalBlock(coordinate, type, sequence, null, null, raw);
        try {
            switch (type) {
                case Jbd2.TYPE_DESCRIPTOR -> {
                    Boolean tail = checkTail(raw, checksumType, out, false);
                    int off = Jbd2.HEADER_LEN;
                    while (off + Jbd2.TAG_LEN <= raw.length - (checksumType == Jbd2.CHECKSUM_CRC32C ? 4 : 0)) {
                        long fsBlock = b.getInt(off) & 0xFFFFFFFFL;
                        int flags = b.getInt(off + 4);
                        out.tags.add(new JournalBlock.Tag(fsBlock, flags,
                                b.getInt(off + 8) & 0xFFFFFFFFL));
                        if ((flags & Jbd2.FLAG_LAST_TAG) != 0) break;
                        off += Jbd2.TAG_LEN;
                    }
                    out.checksumOk = tail;
                    if (out.tags.isEmpty()) {
                        return new JournalBlock(coordinate, type, sequence, tail,
                                "descriptor without tags", raw);
                    }
                }
                case Jbd2.TYPE_COMMIT -> {
                    if (checksumType == Jbd2.CHECKSUM_CRC32C) {
                        long want = b.getInt(12) & 0xFFFFFFFFL;
                        long got = crc32cPrefix(raw, 12);
                        out.checksumOk = want == got;
                    }
                }
                case Jbd2.TYPE_REVOKE -> {
                    Boolean tail = checkTail(raw, checksumType, out, true);
                    int count = b.getInt(12);
                    if (count < 0 || 16 + count * 4 > raw.length
                            - (checksumType == Jbd2.CHECKSUM_CRC32C ? 4 : 0)) {
                        return new JournalBlock(coordinate, type, sequence, tail,
                                "revoke count out of range", raw);
                    }
                    for (int i = 0; i < count; i++) {
                        out.revokeBlocks.add(b.getInt(16 + i * 4) & 0xFFFFFFFFL);
                    }
                    out.checksumOk = tail;
                }
                case Jbd2.TYPE_SUPERBLOCK_V1, Jbd2.TYPE_SUPERBLOCK_V2 -> {
                    out.checksumOk = null;
                }
                default -> {
                    return new JournalBlock(coordinate, -1, sequence, null,
                            "unknown block type " + type, raw);
                }
            }
        } catch (RuntimeException e) {
            return new JournalBlock(coordinate, -1, sequence, out.checksumOk,
                    "parse failure: " + e.getMessage(), raw);
        }
        if (out.checksumOk != null && !out.checksumOk) {
            JournalBlock bad = new JournalBlock(coordinate, type, sequence, false,
                    typeNameLabel(type) + " checksum mismatch", raw);
            bad.tags.addAll(out.tags);
            bad.revokeBlocks.addAll(out.revokeBlocks);
            return bad;
        }
        return out;
    }

    private static String typeNameLabel(int type) {
        return switch (type) {
            case 1 -> "descriptor";
            case 2 -> "commit";
            case 3 -> "revoke";
            default -> "block";
        };
    }

    private static Boolean checkTail(byte[] raw, int checksumType, JournalBlock out,
                                     boolean revokeHeader) {
        if (checksumType != Jbd2.CHECKSUM_CRC32C) return null;
        ByteBuffer b = bb(raw);
        long want = b.getInt(raw.length - 4) & 0xFFFFFFFFL;
        long got = crc32cPrefix(raw, raw.length - 4);
        return want == got;
    }
}
