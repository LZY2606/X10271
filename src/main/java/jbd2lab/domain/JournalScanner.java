package jbd2lab.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a raw journal device image into per-block views, then groups them into
 * candidate transactions. Geometry (ring start, capacity, wrap) is supplied by the
 * caller from the journal superblock. Sequence numbers are cyclic: physical ring
 * order, never ordinary integer sorting, drives grouping.
 */
public final class JournalScanner {

    public Scan scanBlocks(byte[] journal, int blockSize, int ringStartAbs, int ringCapacityBlocks) {
        List<JournalBlock> blocks = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (int ringIndex = 0; ringIndex < ringCapacityBlocks; ringIndex++) {
            int abs = ringStartAbs + ringIndex;
            int off = abs * blockSize;
            if (off + blockSize > journal.length) {
                diagnostics.add("ring slot " + ringIndex + " (abs " + abs + ") lies beyond journal image");
                break;
            }
            byte[] raw = new byte[blockSize];
            System.arraycopy(journal, off, raw, 0, blockSize);
            blocks.add(parseBlock(raw, ringIndex, abs, blockSize, diagnostics));
        }
        return new Scan(blocks, ringStartAbs, ringCapacityBlocks, diagnostics);
    }

    private JournalBlock parseBlock(byte[] raw, int ringIndex, int abs, int blockSize, List<String> diag) {
        String parseError = null;
        long magic = Bytes.getBE32(raw, 0);
        int type = (int) Bytes.getBE32(raw, 4);
        long sequence = Bytes.getBE32(raw, 8);
        long stored = 0;
        long computed = 0;
        boolean matches = true;
        if (magic != Jbd2Format.JBD2_MAGIC) {
            parseError = "bad journal magic 0x" + Long.toHexString(magic);
            return new JournalBlock(ringIndex, abs, type, sequence, raw, stored, computed, false, parseError);
        }
        switch (type) {
            case Jbd2Format.DESC_BLOCK, Jbd2Format.REVOKE_BLOCK -> {
                stored = Bytes.getBE32(raw, blockSize - Jbd2Format.CHECKSUM_SIZE);
                computed = Crc32c.of(raw, 0, blockSize - Jbd2Format.CHECKSUM_SIZE);
                matches = stored == computed;
                if (!matches) {
                    diag.add(Jbd2Format.typeName(type) + " block abs=" + abs + " seq=" + sequence
                            + " body CRC mismatch");
                }
            }
            case Jbd2Format.COMMIT_BLOCK -> {
                stored = Bytes.getBE32(raw, Jbd2Format.COMMIT_CHECKSUM_OFF);
                int cksumType = (int) Bytes.getBE32(raw, 12);
                if (cksumType != Jbd2Format.CKSUM_V3_CRC32C) {
                    parseError = "unsupported commit checksum type " + cksumType;
                    matches = false;
                } else {
                    byte[] copy = raw.clone();
                    Bytes.putBE32(copy, Jbd2Format.COMMIT_CHECKSUM_OFF, 0);
                    computed = Crc32c.of(copy, 0, blockSize);
                    matches = stored == computed;
                    if (!matches) {
                        diag.add("commit block abs=" + abs + " seq=" + sequence + " CRC mismatch "
                                + "(stored=0x" + Long.toHexString(stored)
                                + " computed=0x" + Long.toHexString(computed) + ")");
                    }
                }
            }
            default -> {
                // plain data block: data CRC verified per descriptor tag during grouping
            }
        }
        return new JournalBlock(ringIndex, abs, type, sequence, raw, stored, computed, matches, parseError);
    }

    private static boolean isMeta(int type) {
        return type == Jbd2Format.DESC_BLOCK || type == Jbd2Format.COMMIT_BLOCK
                || type == Jbd2Format.REVOKE_BLOCK;
    }

    /**
     * Walks the ring in physical order from {@code startRingIndex} for one revolution
     * and groups members into transactions.
     */
    public List<Transaction> groupTransactions(Scan scan, int blockSize, int startRingIndex, int checksumVersion) {
        List<JournalBlock> blocks = scan.blocks();
        int n = blocks.size();
        boolean[] consumed = new boolean[n];
        List<Transaction> txns = new ArrayList<>();

        int idx = Math.floorMod(startRingIndex, n);
        for (int visited = 0; visited < n; visited++) {
            if (!consumed[idx] && isHead(blocks.get(idx))) {
                txns.add(collect(blocks, consumed, idx, checksumVersion));
            }
            idx = (idx + 1) % n;
        }
        return txns;
    }

    private static boolean isHead(JournalBlock b) {
        return b.blockType() == Jbd2Format.DESC_BLOCK || b.blockType() == Jbd2Format.REVOKE_BLOCK;
    }

    private Transaction collect(List<JournalBlock> blocks, boolean[] consumed, int head, int checksumVersion) {
        int n = blocks.size();
        JournalBlock headBlock = blocks.get(head);
        long seq = headBlock.headerSequence();
        consumed[head] = true;
        int last = head;

        Integer revokeAbs = null;
        boolean revokeCrc = true;
        List<Long> revoked = List.of();
        Integer descAbs = null;
        boolean descCrc = true;
        List<BlockRef> refs = new ArrayList<>();
        boolean bodyFail = false;

        JournalBlock cur = headBlock;
        if (cur.blockType() == Jbd2Format.REVOKE_BLOCK) {
            revokeAbs = cur.absBlock();
            revokeCrc = cur.checksumMatches();
            bodyFail |= !revokeCrc;
            revoked = parseRevoked(cur.raw());
            JournalBlock nx = peekNext(blocks, consumed, cur.ringIndex());
            if (nx != null && nx.blockType() == Jbd2Format.DESC_BLOCK && nx.headerSequence() == seq) {
                consumed[nx.ringIndex()] = true;
                last = nx.ringIndex();
                descAbs = nx.absBlock();
                descCrc = nx.checksumMatches();
                bodyFail |= !descCrc;
                cur = nx;
            }
        } else {
            descAbs = cur.absBlock();
            descCrc = cur.checksumMatches();
            bodyFail |= !descCrc;
        }

        if (cur.blockType() == Jbd2Format.DESC_BLOCK) {
            List<Tag> tags = parseTags(cur.raw());
            int cursor = cur.ringIndex();
            for (Tag tag : tags) {
                JournalBlock db = peekNext(blocks, consumed, cursor);
                if (db == null || isMeta(db.blockType()) || db.headerSequence() != seq) {
                    bodyFail = true; // descriptor promised a data block that is not present
                    break;
                }
                consumed[db.ringIndex()] = true;
                last = db.ringIndex();
                cursor = db.ringIndex();
                boolean dataOk = tag.tagBlockCrc() == Crc32c.of(db.raw());
                bodyFail |= !dataOk;
                refs.add(new BlockRef(tag.fsBlock(), db.absBlock(), tag.tagBlockCrc(), dataOk, db.raw()));
            }
        }

        Integer commitAbs = null;
        boolean commitCrc = false;
        long commitStored = 0;
        long commitComputed = 0;
        JournalBlock commit = peekNext(blocks, consumed, last);
        if (commit != null && commit.blockType() == Jbd2Format.COMMIT_BLOCK
                && commit.headerSequence() == seq) {
            consumed[commit.ringIndex()] = true;
            last = commit.ringIndex();
            commitAbs = commit.absBlock();
            commitStored = commit.storedChecksum();
            commitComputed = commit.computedChecksum();
            commitCrc = checksumVersion == Jbd2Format.CKSUM_V1_NONE || commit.checksumMatches();
        }
        bodyFail |= refs.stream().anyMatch(r -> !r.dataCrcMatches());

        TransactionStatus status;
        if (commitAbs == null) {
            status = TransactionStatus.INCOMPLETE;
        } else if (!commitCrc) {
            status = TransactionStatus.COMMIT_CKSUM_FAIL;
        } else if (bodyFail) {
            status = TransactionStatus.BODY_CKSUM_FAIL;
        } else {
            status = TransactionStatus.COMMITTED;
        }

        return new Transaction(seq, status, head, last,
                revokeAbs, revokeCrc, revoked, descAbs, descCrc, refs,
                commitAbs, commitCrc, commitStored, commitComputed);
    }

    private JournalBlock peekNext(List<JournalBlock> blocks, boolean[] consumed, int from) {
        int n = blocks.size();
        for (int step = 1; step <= n; step++) {
            int i = (from + step) % n;
            if (!consumed[i]) {
                return blocks.get(i);
            }
        }
        return null;
    }

    /** Parses descriptor tags from the body area until LAST_TAG or end of block. */
    public static List<Tag> parseTags(byte[] desc) {
        List<Tag> tags = new ArrayList<>();
        int off = Jbd2Format.BLOCK_HEADER_SIZE;
        while (off + Jbd2Format.TAG_SIZE <= desc.length - Jbd2Format.CHECKSUM_SIZE) {
            long blocknr = Bytes.getBE64(desc, off);
            int flags = (int) Bytes.getBE32(desc, off + 8);
            long blockCrc = Bytes.getBE32(desc, off + 12);
            tags.add(new Tag(blocknr, flags, blockCrc, -1));
            boolean last = (flags & Jbd2Format.TAG_FLAG_LAST_TAG) != 0;
            off += Jbd2Format.TAG_SIZE;
            if (last) {
                break;
            }
        }
        return tags;
    }

    /** Parses a v1 revoke table block: 32-bit count then big-endian 64-bit revoked block numbers. */
    public static List<Long> parseRevoked(byte[] revoke) {
        long count = Bytes.getBE32(revoke, Jbd2Format.REVOKE_COUNT_OFF);
        List<Long> out = new ArrayList<>();
        int off = Jbd2Format.REVOKE_ENTRIES_OFF;
        for (long i = 0; i < count && off + 8 <= revoke.length - Jbd2Format.CHECKSUM_SIZE; i++) {
            out.add(Bytes.getBE64(revoke, off));
            off += 8;
        }
        return out;
    }
}
