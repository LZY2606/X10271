package jrnl.derive;

import jrnl.domain.*;
import jrnl.jbd2.JournalCodec;

import java.util.*;

/**
 * Walks the physical journal ring from its start exactly once.
 *
 * No integer sorting of sequence numbers happens anywhere: ordering is the
 * physical ring order (ring slot, with wrap), and sequence comparisons use the
 * ring arithmetic in {@link Seq}.
 */
public final class RingScanner {

    private RingScanner() {
    }

    public static ScanResult scan(Snapshot snapshot) {
        BlockSize bs = snapshot.blockSize();
        List<String> diags = new ArrayList<>(snapshot.diagnostics());

        int ringSlots = resolveRingSlots(snapshot, diags);
        if (!bs.valid()) {
            diags.add("block size invalid/unconvertible: " + bs.diagnostic()
                    + " — raw value retained, scan cannot proceed");
            RingPos start = new RingPos(snapshot.ringStart().imageIndex(), 0, Math.max(ringSlots, 1));
            return new ScanResult(start, Math.max(ringSlots, 1), Seq.of(0), List.of(), List.of(), diags);
        }

        int startIdx = snapshot.ringStart().imageIndex();
        Map<Integer, RawBlock> byIndex = new HashMap<>();
        for (RawBlock rb : snapshot.journalBlocks()) {
            byIndex.put(rb.imageIndex(), rb);
            if (rb.length() != bs.bytes()) {
                diags.add("src:block:" + rb.imageIndex() + " length " + rb.length()
                        + " != block size " + bs.bytes());
            }
        }

        List<ScanBlock> blocks = new ArrayList<>();
        Seq anchor = Seq.of(0);
        int pendingData = 0;
        long pendingSeq = 0;
        RingPos pendingDescPos = null;
        List<DescriptorTag> pendingTags = List.of();
        boolean anchorSet = false;

        for (int slot = 0; slot < ringSlots; slot++) {
            int img = startIdx + slot;
            RingPos pos = new RingPos(img, slot, ringSlots);
            RawBlock rb = byIndex.get(img);
            if (rb == null) {
                blocks.add(new ScanBlock(pos, null, List.of(), List.of(), null, null, null,
                        "absent journal block at " + pos.coord()));
                pendingData = 0;
                continue;
            }
            byte[] bytes = rb.bytes();
            BlockHeader hdr = JournalCodec.parseHeader(bytes);

            if (!anchorSet && hdr != null && hdr.diagnostic() == null
                    && hdr.type() != 0 && hdr.sequence() != 0) {
                anchor = Seq.of(hdr.sequence());
                anchorSet = true;
            }

            ChecksumVersion cv = snapshot.checksumVersion();
            if (hdr.diagnostic() == null && hdr.type() == BlockHeader.DESCRIPTOR) {
                pendingTags = JournalCodec.parseTags(bytes);
                long expected = cv == ChecksumVersion.NONE ? null
                        : JournalCodec.tailChecksum(bytes);
                Boolean ok = JournalCodec.verifyDescriptor(bytes, cv);
                blocks.add(new ScanBlock(pos, hdr, pendingTags, List.of(), expected,
                        expected == null ? null : (long) (int) ChecksumsShim.compute(cv, bytes, true),
                        ok, ok != null && !ok ? "descriptor checksum mismatch" : null));
                pendingData = (int) pendingTags.stream()
                        .filter(t -> !t.deleted() && !t.sameUuid()).count();
                pendingSeq = hdr.sequence();
                pendingDescPos = pos;
            } else if (hdr.diagnostic() == null && hdr.type() == BlockHeader.COMMIT_BLOCK) {
                Boolean ok = JournalCodec.verifyCommit(bytes, cv);
                long expected = JournalCodec.tailChecksum(bytes);
                long actual = ChecksumsShim.compute(cv, bytes, false);
                blocks.add(new ScanBlock(pos, hdr, List.of(), List.of(), expected, actual, ok,
                        ok != null && !ok ? "commit checksum mismatch — boundary uncertainty retained" : null));
                pendingData = 0;
            } else if (hdr.diagnostic() == null && hdr.type() == BlockHeader.REVOKE_BLOCK) {
                List<Long> rev = JournalCodec.parseRevokeEntries(bytes);
                blocks.add(new ScanBlock(pos, hdr, List.of(), rev, null, null, null, null));
                pendingData = 0;
            } else if (hdr.diagnostic() == null && hdr.type() == BlockHeader.SUPERBLOCK_V2) {
                blocks.add(new ScanBlock(pos, hdr, List.of(), List.of(), null, null, null, null));
                pendingData = 0;
            } else {
                if (hdr.diagnostic() != null) {
                    if (pendingData > 0) {
                        BlockHeader synth = new BlockHeader(0, pendingSeq, 0, "data", null);
                        blocks.add(new ScanBlock(pos, synth, List.of(), List.of(), null, null, null,
                                "unheaded bytes consumed as data of seq " + Seq.of(pendingSeq).hex()
                                        + " started at " + pendingDescPos.coord() + "; " + hdr.diagnostic()));
                        pendingData--;
                    } else {
                        blocks.add(new ScanBlock(pos, hdr, List.of(), List.of(), null, null, null,
                                "stale/unheaded block outside any descriptor chain: " + hdr.diagnostic()));
                    }
                } else if (pendingData > 0) {
                    BlockHeader synth = new BlockHeader(0, pendingSeq, 0, "data", null);
                    blocks.add(new ScanBlock(pos, synth, List.of(), List.of(), null, null, null,
                            "data block of seq " + Seq.of(pendingSeq).hex()));
                    pendingData--;
                } else {
                    blocks.add(new ScanBlock(pos, hdr, List.of(), List.of(), null, null, null,
                            "stale block with magic but not part of a live chain (type="
                                    + hdr.type() + ", seq=" + Seq.of(hdr.sequence()).hex() + ")"));
                }
            }
        }

        List<TxnGroup> group = groupBySequence(blocks, diags);
        return new ScanResult(new RingPos(startIdx, 0, ringSlots), ringSlots, anchor, blocks, group, diags);
    }

    private static int resolveRingSlots(Snapshot s, List<String> diags) {
        int start = s.ringStart().imageIndex();
        int maxIdx = s.journalBlocks().stream().mapToInt(RawBlock::imageIndex).max().orElse(start);
        int slots = Math.max(1, maxIdx - start + 1);
        return slots;
    }

    private static List<TxnGroup> groupBySequence(List<ScanBlock> blocks, List<String> diags) {
        // Keep ring order; map seq -> accumulator, but the same seq can only be
        // opened once per lap — a second descriptor with the same seq means the
        // ring wrapped into a new lap (cannot happen in one walk; stale tail
        // descriptors are grouped too and flagged via missing/overwritten head).
        java.util.SortedMap<Long, Acc> bySeq = new java.util.TreeMap<>();
        Map<Long, Integer> seqFirstSlot = new HashMap<>();

        int pendingSinceSlot = -1;
        long pendingSeq = -1;
        for (ScanBlock sb : blocks) {
            BlockHeader h = sb.header();
            if (h == null) {
                continue;
            }
            if (sb.isDescriptor()) {
                Acc acc = bySeq.computeIfAbsent(h.sequence(), k -> new Acc());
                acc.descriptorPos = sb.pos();
                acc.memberPos.add(sb.pos());
                acc.lastTags = sb.tags();
                acc.descriptorChecksumOk = sb.checksumOk();
                pendingSeq = h.sequence();
                pendingSinceSlot = sb.pos().ringSlot();
                seqFirstSlot.putIfAbsent(h.sequence(), sb.pos().ringSlot());
            } else if (sb.isCommit()) {
                Acc acc = bySeq.computeIfAbsent(h.sequence(), k -> new Acc());
                acc.commitPos = sb.pos();
                acc.memberPos.add(sb.pos());
                acc.commitChecksumOk = sb.checksumOk();
            } else if (sb.isRevoke()) {
                Acc acc = bySeq.computeIfAbsent(h.sequence(), k -> new Acc());
                acc.revokePos = sb.pos();
                acc.memberPos.add(sb.pos());
                acc.revokes.addAll(sb.revokedBlocks());
            } else if ("data".equals(h.typeName())) {
                Acc acc = bySeq.get(pendingSeq);
                if (acc != null) {
                    acc.memberPos.add(sb.pos());
                    acc.dataPos.add(sb.pos());
                }
            }
        }

        List<TxnGroup> out = new ArrayList<>();
        for (Map.Entry<Long, Acc> e : bySeq.entrySet()) {
            Acc acc = e.getValue();
            Seq seq = Seq.of(e.getKey());
            List<String> txDiags = new ArrayList<>();
            List<TxnGroup.DataWrite> writes = new ArrayList<>();
            boolean hasDescriptor = acc.descriptorPos != null;
            boolean hasCommit = acc.commitPos != null;
            boolean hasRevoke = acc.revokePos != null;

            if (hasDescriptor && acc.descriptorChecksumOk != null && !acc.descriptorChecksumOk) {
                txDiags.add("descriptor checksum failure at " + acc.descriptorPos.coord());
            }
            if (hasDescriptor) {
                List<RingPos> dp = new ArrayList<>(acc.dataPos);
                int di = 0;
                for (DescriptorTag t : acc.lastTags) {
                    if (t.deleted() || t.sameUuid()) {
                        continue;
                    }
                    if (di < dp.size()) {
                        writes.add(new TxnGroup.DataWrite(dp.get(di), t.fsBlock(), t));
                        di++;
                    } else {
                        txDiags.add("tag for fs block " + t.fsBlock() + " has no data block (torn chain)");
                    }
                }
                if (di < dp.size()) {
                    txDiags.add((dp.size() - di) + " orphan data block(s) with no tag");
                }
            }

            String status;
            if (!hasCommit) {
                status = hasDescriptor ? "HALF_NO_COMMIT" : "HEAD_OVERWRITTEN_ORPHAN";
                txDiags.add("no commit block for seq " + seq.hex() + " — half transaction, not replayable");
            } else if (acc.commitChecksumOk != null && !acc.commitChecksumOk) {
                status = "COMMIT_CHECKSUM_BAD";
                txDiags.add("commit checksum bad at " + acc.commitPos.coord()
                        + " — legal replays diverge at this boundary");
            } else if (!hasDescriptor && !hasRevoke) {
                status = "COMMIT_WITHOUT_BODY";
                txDiags.add("commit with no descriptor/revoke body — not replayable");
            } else {
                status = "COMPLETE";
            }

            out.add(new TxnGroup(seq, new ArrayList<>(acc.memberPos), acc.descriptorPos,
                    acc.commitPos, writes, new ArrayList<>(acc.revokes), status, txDiags));
        }
        return out;
    }

    private static final class Acc {
        RingPos descriptorPos;
        RingPos commitPos;
        RingPos revokePos;
        final List<RingPos> memberPos = new ArrayList<>();
        final List<RingPos> dataPos = new ArrayList<>();
        List<DescriptorTag> lastTags = List.of();
        final List<Long> revokes = new ArrayList<>();
        Boolean descriptorChecksumOk;
        Boolean commitChecksumOk;
    }
}
