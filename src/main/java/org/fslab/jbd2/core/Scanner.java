package org.fslab.jbd2.core;

import org.fslab.jbd2.model.*;

import java.util.*;
import java.util.zip.CRC32;

/**
 * Physical-order scan of a JBD2 journal image.
 *
 * <p>The scanner never decides replayability by guessing: a descriptor with no
 * commit, a commit with no descriptor and any checksum failure all survive as
 * explicit group/block diagnostics. Block types are identified from journal
 * headers; every other block is treated as payload data.
 */
public final class Scanner {
    private Scanner() {}

    /** Lab convention: descriptor tag checksums are 2 little-endian bytes of the
     *  CRC of (descriptor-with-all-tag-checksums-zeroed + the payload block). */
    public static long tagCrc(boolean v2, byte[]... parts) {
        if (v2) {
            return Crc32c.of(parts);
        }
        CRC32 crc = new CRC32();
        for (byte[] p : parts) crc.update(p);
        return crc.getValue();
    }

    public static long commitCrc(boolean v2, byte[]... parts) {
        return tagCrc(v2, parts);
    }

    public static List<BlockScan> scanBlocks(byte[][] blocks, JournalSuperblock sb) {
        boolean v2 = sb != null && sb.checksumsV2();
        List<BlockScan> out = new ArrayList<>();
        for (int phys = 0; phys < blocks.length; phys++) {
            out.add(scanOne(phys, blocks[phys], v2));
        }
        return out;
    }

    private static BlockScan scanOne(int phys, byte[] raw, boolean v2) {
        if (raw == null) {
            return BlockScan.unknown(phys, List.of(Diagnostic.warn("BLOCK_MISSING",
                    "journal-block@" + phys, "no block bytes preserved; nothing to scan", "")));
        }
        Buffer buf = new Buffer(raw);
        long magic = raw.length >= 4 ? buf.uintAt(0) : 0;
        int type = raw.length >= 8 ? buf.intAt(4) : 0;
        long seq = raw.length >= 12 ? buf.uintAt(8) : null;
        List<Diagnostic> diags = new ArrayList<>();
        if (magic == (Jbd2Const.DESC_BLOCK_MAGIC & 0xFFFFFFFFL)) {
            return switch (type) {
                case Jbd2Const.DESCRIPTOR_BLOCK -> scanDescriptor(phys, raw, v2, diags);
                case Jbd2Const.COMMIT_BLOCK -> scanCommit(phys, raw, v2, diags, seq);
                case Jbd2Const.REVOKE_BLOCK -> scanRevoke(phys, raw, v2, diags, seq);
                case Jbd2Const.SUPERBLOCK_V2 -> BlockScan.unknown(phys, List.of());
                default -> BlockScan.unknown(phys, List.of(Diagnostic.warn("BLOCK_TYPE",
                        "journal-block@" + phys, "unknown journal block type " + type,
                        String.valueOf(type))));
            };
        }
        return BlockScan.unknown(phys, List.of());
    }

    private static BlockScan scanDescriptor(int phys, byte[] raw, boolean v2,
                                            List<Diagnostic> diags) {
        Buffer buf = new Buffer(raw);
        long seq = buf.uintAt(8);
        int tagBytes = is64Bit(raw) ? Jbd2Const.TAG_BYTES_64 : Jbd2Const.TAG_BYTES_32;
        List<TagRecord> tags = new ArrayList<>();
        int off = Jbd2Const.HEADER_BYTES;
        int index = 0;
        while (off + tagBytes <= raw.length) {
            long blocknr = buf.uintAt(off);
            long flagsWord = tagBytes == Jbd2Const.TAG_BYTES_64
                    ? buf.uintAt(off + 8)
                    : (buf.uintAt(off + 4) & 0xFFFFL);
            int flags = (int) flagsWord;
            if (blocknr == 0 && flags == 0 && restZero(raw, off)) break;
            int checksum = buf.intAt(off + 4) & 0xFFFF;
            boolean sameUuid = (flags & Jbd2Const.FLAG_SAME_UUID) != 0;
            boolean escape = (flags & Jbd2Const.FLAG_ESCAPE) != 0;
            boolean deleted = (flags & Jbd2Const.FLAG_DELETED) != 0;
            boolean last = (flags & Jbd2Const.FLAG_LAST_TAG) != 0;
            int uuidSkip = sameUuid ? 0 : Jbd2Const.UUID_BYTES;
            TagRecord rec = new TagRecord(index, off, blocknr, flags, sameUuid, escape,
                    deleted, last, String.format("%04x", checksum), null,
                    null);
            tags.add(rec);
            off += tagBytes + uuidSkip;
            index++;
            if (last) break;
            if (index > 4096) {
                diags.add(Diagnostic.error("DESC_TAGS", "journal-block@" + phys,
                        "descriptor tag run never terminates", ""));
                break;
            }
        }
        if (tags.isEmpty()) {
            diags.add(Diagnostic.error("DESC_EMPTY", "journal-block@" + phys,
                    "descriptor block carries no tags", ""));
        }
        return new BlockScan(phys, BlockType.DESCRIPTOR, seq, tags, List.of(), null, null, null,
                diags);
    }

    private static boolean is64Bit(byte[] raw) {
        if (raw.length < 1024) return false;
        for (int i = 12; i < 1024; i += Jbd2Const.TAG_BYTES_32) {
            long a = readLE32(raw, i);
            long b = readLE32(raw, i + 4);
            if (a != 0 || b != 0) {
                int tag32Flags = (int) (b & 0xFFFFL);
                boolean plausible32 = tag32Flags <= 0xF;
                return !plausible32;
            }
        }
        return false;
    }

    private static boolean restZero(byte[] raw, int from) {
        for (int i = from; i < raw.length; i++) if (raw[i] != 0) return false;
        return true;
    }

    private static long readLE32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8)
                | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }

    private static BlockScan scanCommit(int phys, byte[] raw, boolean v2,
                                        List<Diagnostic> diags, long seq) {
        long cs = readLE32(raw, raw.length - 4);
        byte[] zeroed = raw.clone();
        zeroed[raw.length - 4] = 0;
        zeroed[raw.length - 3] = 0;
        zeroed[raw.length - 2] = 0;
        zeroed[raw.length - 1] = 0;
        long actual = commitCrc(v2, zeroed);
        boolean ok = (actual & 0xFFFFFFFFL) == cs;
        if (!ok) {
            diags.add(Diagnostic.error("COMMIT_CHECKSUM", "journal-block@" + phys,
                    "commit block checksum mismatch under checksum type " + (v2 ? 2 : 1),
                    String.format("%08x", cs)));
        }
        return new BlockScan(phys, BlockType.COMMIT, seq, List.of(), List.of(), seq,
                String.format("%08x", cs), ok, diags);
    }

    private static BlockScan scanRevoke(int phys, byte[] raw, boolean v2,
                                        List<Diagnostic> diags, long seq) {
        int count = new Buffer(raw).intAt(Jbd2Const.HEADER_BYTES);
        List<Long> blocks = new ArrayList<>();
        int off = Jbd2Const.HEADER_BYTES + Jbd2Const.REVOKE_COUNT_BYTES;
        for (int i = 0; i < count; i++) {
            if (off + 4 > raw.length) {
                diags.add(Diagnostic.error("REVOKE_RUN", "journal-block@" + phys,
                        "revoke count overruns block: " + count, String.valueOf(count)));
                break;
            }
            blocks.add(readLE32(raw, off));
            off += 4;
        }
        if (count < 0) {
            diags.add(Diagnostic.error("REVOKE_COUNT", "journal-block@" + phys,
                    "negative revoke count", String.valueOf(count)));
        }
        return new BlockScan(phys, BlockType.REVOKE, seq, List.of(), blocks, null, null, null,
                diags);
    }

    /**
     * Assemble groups in physical scan order starting at the ring origin, and
     * resolve which payload block each descriptor tag consumes.
     */
    public static List<TransactionGroup> assembleGroups(List<BlockScan> scans,
                                                        byte[][] blocks,
                                                        JournalSuperblock sb,
                                                        int ringStart) {
        List<TransactionGroup> groups = new ArrayList<>();
        int i = 0;
        while (i < scans.size()) {
            BlockScan bs = scans.get(i);
            if (bs.type() != BlockType.DESCRIPTOR) {
                i++;
                continue;
            }
            long seq = bs.headerSequence();
            List<Integer> members = new ArrayList<>();
            members.add(i);
            List<TagRecord> resolvedTags = new ArrayList<>(bs.tags());
            List<TransactionGroup.DataRef> refs = new ArrayList<>();
            List<Integer> revokeBlocks = new ArrayList<>();
            List<Long> revoked = new ArrayList<>();
            List<Diagnostic> diags = new ArrayList<>(bs.diagnostics());
            int j = i + 1;
            int tagCursor = 0;
            Integer commitBlock = null;
            boolean commitOk = false;
            List<Diagnostic> commitDiags = new ArrayList<>();
            while (j < scans.size()) {
                BlockScan cur = scans.get(j);
                if (cur.type() == BlockType.DESCRIPTOR) break;
                members.add(j);
                if (cur.type() == BlockType.REVOKE) {
                    revokeBlocks.add(j);
                    revoked.addAll(cur.revokedFileBlocks());
                    diags.addAll(cur.diagnostics());
                } else if (cur.type() == BlockType.COMMIT) {
                    if (!Objects.equals(cur.commitSequence(), seq)) {
                        diags.add(Diagnostic.error("TX_SEQ_MISMATCH",
                                "journal-block@" + j,
                                "commit sequence " + cur.commitSequence()
                                        + " does not match descriptor sequence " + seq,
                                String.valueOf(cur.commitSequence())));
                    }
                    commitBlock = j;
                    commitOk = Boolean.TRUE.equals(cur.commitChecksumOk());
                    commitDiags.addAll(cur.diagnostics());
                    j++;
                    break;
                } else {
                    if (tagCursor < resolvedTags.size()) {
                        TagRecord t = resolvedTags.get(tagCursor);
                        Boolean tagOk = verifyTag(t, blocks[t.tagOffset() >= 0 ? i : i],
                                blocks[j], sb);
                        resolvedTags.set(tagCursor, withData(t, j, tagOk));
                        if (Boolean.FALSE.equals(tagOk)) {
                            diags.add(Diagnostic.error("TAG_CHECKSUM",
                                    "journal-block@" + i + "#tag" + tagCursor,
                                    "descriptor tag checksum mismatch for payload block " + j,
                                    t.tagChecksumHex()));
                        }
                        refs.add(new TransactionGroup.DataRef(resolvedTags.get(tagCursor), j));
                        tagCursor++;
                    } else {
                        diags.add(Diagnostic.warn("ORPHAN_DATA", "journal-block@" + j,
                                "payload block has no descriptor tag", ""));
                    }
                }
                j++;
            }
            diags.addAll(commitDiags);
            boolean committed = commitBlock != null;
            boolean checksumOk = committed && commitOk
                    && resolvedTags.stream().allMatch(t -> !Boolean.FALSE.equals(t.tagChecksumOk()))
                    && diags.stream().noneMatch(d -> "ERROR".equals(d.severity()));
            groups.add(new TransactionGroup(seq, i, resolvedTags, refs, revokeBlocks,
                    revoked, commitBlock, committed, checksumOk, members, diags));
            i = commitBlock != null ? j : (j > i + 1 ? j : i + 1);
        }
        return groups;
    }

    private static TagRecord withData(TagRecord t, int dataBlock, Boolean ok) {
        return new TagRecord(t.tagIndex(), t.tagOffset(), t.fileBlock(), t.flags(),
                t.sameUuid(), t.escape(), t.deleted(), t.lastTag(),
                t.tagChecksumHex(), ok, dataBlock);
    }

    private static Boolean verifyTag(TagRecord tag, byte[] descriptor, byte[] payload,
                                     JournalSuperblock sb) {
        Integer expected;
        try {
            expected = Integer.parseInt(tag.tagChecksumHex(), 16);
        } catch (NumberFormatException ex) {
            return false;
        }
        byte[] descZeroed = descriptor.clone();
        int p = tag.tagOffset() + 4;
        descZeroed[p] = 0;
        descZeroed[p + 1] = 0;
        boolean v2 = sb != null && sb.checksumsV2();
        long actual = tagCrc(v2, descZeroed, payload);
        return (actual & 0xFFFF) == expected;
    }

    public static ScanResult scan(byte[][] blocks, JournalSuperblock sb, int ringStart) {
        List<BlockScan> scans = scanBlocks(blocks, sb);
        List<TransactionGroup> groups = assembleGroups(scans, blocks, sb, ringStart);
        List<Long> seqOrder = groups.stream().map(TransactionGroup::sequence).distinct().toList();
        List<Diagnostic> all = new ArrayList<>(sb == null ? List.of() : sb.diagnostics());
        scans.forEach(s -> all.addAll(s.diagnostics()));
        groups.forEach(g -> all.addAll(g.diagnostics()));
        Q ring = Q.of(String.valueOf(ringStart), ringStart,
                "blocks (physical, 0-based inside journal)", "s_first");
        Q observed = Q.of(String.valueOf(blocks.length), blocks.length,
                "blocks (physical, imported)", "import.length");
        return new ScanResult(sb, ring, observed, scans, groups, seqOrder, all);
    }
}
