package jbd2lab.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans a physical journal ring into transaction candidates without collapsing
 * uncertainty:
 *
 *  - traversal starts at the ring-start coordinate and proceeds along the ring; the
 *    scan may cross the physical end (wrap), and transaction order is decided by JBD2
 *    sequence on the circular axis, never by ordinary integer sorting,
 *  - only descriptor-anchored groups are recognized; data blocks are consumed by tags,
 *  - a descriptor whose data/commit lies at/after the crash point (or past the ring end)
 *    is kept as a half transaction,
 *  - asserted descriptor/data/commit checksums are verified under the journal
 *    superblock's checksum version; the first break marks the boundary uncertain and a
 *    resync continuation slot is recorded (both interpretations are retained),
 *  - revoke blocks attach to the open transaction sequence and feed sequence-range
 *    revocation during replay.
 */
public final class Scanner {
    private final List<JournalBlock> ring;
    private final Params params;
    private final int crashCutoff;

    public Scanner(List<JournalBlock> ring, Params params, int crashCutoff) {
        this.ring = ring;
        this.params = params;
        this.crashCutoff = crashCutoff;
    }

    public ScanResult scan() {
        ScanResult r = new ScanResult();
        r.ringLength = ring.size();
        r.ringStart = (int) params.ringStart.value();
        r.blockSize = params.blockSize.invalid() ? -1 : (int) params.blockSize.value();
        r.checksumType = params.checksumType;
        for (int i = 0; i < ring.size(); i++) r.slotRoles.add("gap");

        if (params.blockSize.invalid()) {
            r.blockSizeInvalid = true;
            r.diag("error", "invalid_block_size",
                    "block size cannot be normalized: " + params.blockSize.diagnostic(), r.ringStart);
            return r;
        }

        int n = ring.size();
        int start = n == 0 ? 0 : Math.floorMod(r.ringStart, n);
        Txn open = null;
        int pos = start;
        int visited = 0;
        while (visited < n) {
            boolean postCrash = crashCutoff > 0 && pos >= crashCutoff;
            JournalBlock blk = ring.get(pos);

            if (postCrash) {
                r.slotRoles.set(pos, "post_crash");
                if (open != null) {
                    open.truncated = true;
                    r.diag("warn", "half_transaction_at_crash",
                            "transaction seq=" + open.sequence + " reaches the crash point at slot "
                                    + pos + " without a commit", open.descSlot);
                    open.endSlot = pos;
                    r.txns.add(open);
                    open = null;
                }
                pos++; visited++;
                continue;
            }

            if (pos == 0 && visited > 0) r.wrapped = true;

            if (!blk.headerLooksValid() || blk.bytes.length != r.blockSize) {
                if (open != null) {
                    r.diag("warn", "non_journal_block_in_txn",
                            "block inside open transaction seq=" + open.sequence
                                    + " is not a journal header; group is broken", pos);
                    open.truncated = true;
                    open.endSlot = pos;
                    r.txns.add(open);
                    open = null;
                }
                pos++; visited++;
                continue;
            }

            switch (blk.type()) {
                case JBD2.DESC_BLOCK: {
                    if (open != null) {
                        r.diag("warn", "descriptor_without_commit",
                                "new descriptor at slot " + pos + " while seq=" + open.sequence
                                        + " had no commit; recorded as half transaction", pos);
                        open.truncated = true;
                        open.endSlot = pos;
                        r.txns.add(open);
                    }
                    open = new Txn(blk.sequence(), pos);
                    r.slotRoles.set(pos, "descriptor:seq=" + open.sequence);
                    verifyDescriptorCrc(blk, open, r, pos);
                    List<TagRec> parsed = parseTags(blk, r, pos);
                    int dataPos = pos + 1;
                    int dataVisited = 0;
                    for (TagRec tag : parsed) {
                        if (dataPos >= n || (crashCutoff > 0 && dataPos >= crashCutoff)) {
                            open.truncated = true;
                            r.diag("warn", "data_past_boundary",
                                    "data for fs block " + tag.fsBlock + " (seq=" + open.sequence
                                            + ") would be at slot " + dataPos
                                            + " beyond ring/crash boundary", pos);
                            continue;
                        }
                        JournalBlock data = ring.get(dataPos);
                        r.slotRoles.set(dataPos, "data:fs=" + tag.fsBlock + ":seq=" + open.sequence);
                        if (tag.crcAsserted) verifyDataCrc(tag, data, open, r, dataPos);
                        open.tags.add(tagWithSlot(tag, dataPos));
                        dataPos++;
                        dataVisited++;
                    }
                    visited += 1 + dataVisited;
                    pos = dataPos;
                    break;
                }
                case JBD2.REVOKE_BLOCK: {
                    r.slotRoles.set(pos, "revoke:seq=" + blk.sequence());
                    if (open == null) {
                        open = new Txn(blk.sequence(), pos);
                        open.descSlot = pos;
                    }
                    parseRevokes(blk, open, r, pos);
                    open.revokeSlots.add(pos);
                    pos++; visited++;
                    break;
                }
                case JBD2.COMMIT_BLOCK: {
                    if (open == null || open.sequence != blk.sequence()) {
                        r.diag("warn", "orphan_commit",
                                "commit block seq=" + blk.sequence()
                                        + " has no matching open descriptor", pos);
                        r.slotRoles.set(pos, "orphan_commit:seq=" + blk.sequence());
                    } else {
                        open.commitSlot = pos;
                        open.endSlot = pos + 1;
                        open.commitChecksumOk = verifyCommitCrc(blk, r, pos);
                        r.slotRoles.set(pos, "commit:seq=" + open.sequence);
                        open.complete = true;
                        if (!open.checksumOk()) markUncertain(r, open, pos + 1);
                        r.txns.add(open);
                        open = null;
                    }
                    pos++; visited++;
                    break;
                }
                case JBD2.SUPERBLOCK_V2:
                    r.slotRoles.set(pos, "journal_superblock");
                    pos++; visited++;
                    break;
                default:
                    r.diag("info", "unknown_block_type",
                            "unrecognized journal block type " + blk.type(), pos);
                    r.slotRoles.set(pos, "unknown:type=" + blk.type());
                    pos++; visited++;
            }
        }
        if (open != null) {
            open.truncated = true;
            if (open.tags.isEmpty() && open.revokes.isEmpty()) {
                r.diag("warn", "half_transaction",
                        "transaction seq=" + open.sequence
                                + " reaches the ring end without a commit", open.descSlot);
            } else {
                r.diag("warn", "half_transaction",
                        "transaction seq=" + open.sequence
                                + " reaches the ring end without a commit", open.descSlot);
            }
            open.endSlot = Math.min(pos, n);
            r.txns.add(open);
        }
        if (!r.txns.isEmpty()) r.headSequence = r.txns.get(0).sequence;
        return r;
    }

    private static TagRec tagWithSlot(TagRec t, int slot) {
        return new TagRec(t.index, t.fsBlock, t.flags, t.expectedCrc, t.crcAsserted,
                slot, t.tagByteOffset);
    }

    private void verifyDescriptorCrc(JournalBlock blk, Txn txn, ScanResult r, int slot) {
        byte[] b = blk.bytes();
        long asserted = blk.readU32(b.length - JBD2.DESC_TRAILER_SIZE);
        byte[] z = b.clone();
        for (int i = b.length - 4; i < b.length; i++) z[i] = 0;
        long actual = Crc32c.of(z) & 0xFFFFFFFFL;
        if (asserted != 0L && asserted != actual) {
            r.diag("error", "descriptor_checksum_failed",
                    "descriptor seq=" + blk.sequence() + " at slot " + slot
                            + ": crc32c asserted=" + Long.toHexString(asserted)
                            + " actual=" + Long.toHexString(actual), slot);
            txn.descChecksumOk = false;
            markUncertain(r, txn, slot + 1);
        }
    }

    private List<TagRec> parseTags(JournalBlock blk, ScanResult r, int slot) {
        List<TagRec> tags = new ArrayList<>();
        byte[] b = blk.bytes();
        int off = JBD2.HEADER_SIZE;
        int idx = 0;
        while (off + JBD2.TAG_SIZE <= b.length - JBD2.DESC_TRAILER_SIZE) {
            long low = blk.readU32(off);
            int flags = blk.u16(off + 4);
            long high = blk.u16(off + 6) & 0xFFFFL;
            long crc = blk.readU32(off + 8);
            if (low == 0 && flags == 0 && high == 0 && crc == 0) break;
            long fsBlock = low | (high << 32);
            boolean crcOn = (flags & JBD2.TAG_IGNORE_CRC) == 0;
            tags.add(new TagRec(idx, fsBlock, flags & JBD2.TAG_FLAG_MASK, crc, crcOn, -1, off));
            off += JBD2.TAG_SIZE;
            idx++;
            if ((flags & JBD2.TAG_LAST_FLAG) != 0) break;
        }
        if (tags.isEmpty()) r.diag("warn", "empty_descriptor",
                "descriptor without tags at slot " + slot, slot);
        return tags;
    }

    private void verifyDataCrc(TagRec tag, JournalBlock data, Txn txn, ScanResult r, int slot) {
        long actual = Crc32c.of(data.bytes()) & 0xFFFFFFFFL;
        if (actual != tag.expectedCrc) {
            r.diag("error", "data_checksum_failed",
                    "data block fs=" + tag.fsBlock + " seq=" + txn.sequence
                            + ": crc32c asserted=" + Long.toHexString(tag.expectedCrc)
                            + " actual=" + Long.toHexString(actual), slot);
            txn.tagCrcFailures.add("fs=" + tag.fsBlock);
        }
    }

    private boolean verifyCommitCrc(JournalBlock blk, ScanResult r, int slot) {
        long asserted = blk.readU32(JBD2.COMMIT_CRC_OFF);
        byte[] z = blk.bytes().clone();
        for (int i = 0; i < 4; i++) z[JBD2.COMMIT_CRC_OFF + i] = 0;
        long actual = Crc32c.of(z) & 0xFFFFFFFFL;
        if (asserted != actual) {
            r.diag("error", "commit_checksum_failed",
                    "commit seq=" + blk.sequence() + " at slot " + slot
                            + ": crc32c asserted=" + Long.toHexString(asserted)
                            + " actual=" + Long.toHexString(actual), slot);
            return false;
        }
        return true;
    }

    private void parseRevokes(JournalBlock blk, Txn txn, ScanResult r, int slot) {
        int count = (int) blk.readU32(12);
        int off = 16;
        int seen = 0;
        while (off + 4 <= blk.bytes().length && seen < count) {
            long fsBlock = blk.readU32(off);
            txn.revokes.add(new RevokeEntry(fsBlock, slot, seen));
            off += 4;
            seen++;
        }
        if (seen != count) {
            r.diag("warn", "revoke_count_mismatch",
                    "revoke block seq=" + txn.sequence + " declares " + count
                            + " entries but only " + seen + " fit", slot);
        }
    }

    private void markUncertain(ScanResult r, Txn txn, int resyncSlot) {
        txn.boundaryUncertain = true;
        r.uncertainAfterSeq = txn.sequence;
        r.resyncSlot = resyncSlot;
        r.diag("uncertain", "boundary_uncertain_after_checksum",
                "boundary after seq=" + txn.sequence
                        + " is uncertain: strict and resync interpretations are both retained",
                resyncSlot);
    }
}
