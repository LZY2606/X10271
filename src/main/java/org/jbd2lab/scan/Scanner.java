package org.jbd2lab.scan;

import org.jbd2lab.domain.Capture;
import org.jbd2lab.domain.ParameterSet;
import org.jbd2lab.domain.RawBlock;
import org.jbd2lab.layout.Commit;
import org.jbd2lab.layout.Descriptor;
import org.jbd2lab.layout.Jbd2;
import org.jbd2lab.layout.JournalSuperblock;
import org.jbd2lab.layout.Revoke;
import org.jbd2lab.layout.Tag;
import org.jbd2lab.seq.CircularSeq;
import org.jbd2lab.util.Be;
import org.jbd2lab.util.Checksums;
import org.jbd2lab.util.Ids;
import org.jbd2lab.util.Units;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic physical-ring scanner implementing the lab semantics:
 *
 * <ul>
 *   <li>walk the ring from s_first, forward with index wrap, stopping at the crash boundary
 *       (exclusive) or once every populated slot in the traversal window is visited once;</li>
 *   <li>only a transaction whose commit block is physically present and whose framing is intact
 *       is COMPLETE and replayable — a descriptor without a commit is a HALF transaction;</li>
 *   <li>checksum failure breaks trust; framing after the break is kept UNCERTAIN and every
 *       independently resynchronised, fully verified suffix becomes a separate candidate;</li>
 *   <li>sequence order is circular: wrap comparisons use {@link CircularSeq}, never integer sort.</li>
 * </ul>
 */
public final class Scanner {

    public static final String RULE_VERSION = "jbd2-scan-1.0.0";

    private static final int MAX_WALK_SLOTS = 1 << 20;

    public ScanResult scan(Capture capture, ParameterSet params) {
        List<LabEvent> events = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(capture.diagnostics());

        Units.RawLong declared = capture.declaredBlockSize();
        if (!declared.valid()) {
            diagnostics.add(declared.diagnostic());
            events.add(LabEvent.code("BLOCK_SIZE_INVALID").severity(LabEvent.ERROR)
                    .message(declared.diagnostic())
                    .attr("raw", declared.original())
                    .attr("fallbackUnit", Units.U_BYTE)
                    .attr("fallback", capture.strideBytes())
                    .attr("retainedAtUnit", Units.U_BYTE)
                    .build());
        } else {
            events.add(LabEvent.code("BLOCK_SIZE_OK").message("declared block size is a valid power of two")
                    .attr("value", declared.require()).attr("unit", Units.U_BYTE).build());
        }
        int bs = capture.effectiveBlockSize();
        int csumVersion = parseChecksumVersion(capture, events);

        RawBlock sbBlock = capture.ringMap().values().stream()
                .filter(b -> b.physicalRingIndex() == sbIndex(capture))
                .findFirst().orElse(null);
        if (sbBlock != null && sbBlock.bytes().length >= Jbd2.SB_SIZE) {
            JournalSuperblock sb = JournalSuperblock.parse(sbBlock.bytes());
            if (sb.magic() == Jbd2.SUPERBLOCK_MAGIC) {
                int sbCsum = sb.checksumVersion();
                if (sbCsum != csumVersion) {
                    events.add(LabEvent.code("CHECKSUM_VERSION_SOURCE_MISMATCH").severity(LabEvent.WARN)
                            .message("declared checksum version differs from superblock feature bits")
                            .attr("declaredRaw", capture.checksumVersionRaw())
                            .attr("fromSuperblock", sbCsum).build());
                }
            }
        }

        Walk prefix = walk(capture, params, bs, csumVersion, events, false, capture.journalStart(), null);
        List<ScanCandidate> candidates = new ArrayList<>();
        candidates.add(new ScanCandidate("candidate-prefix", true, true,
                List.copyOf(prefix.transactions), List.copyOf(prefix.walk), List.copyOf(prefix.events)));

        for (int i = 0; i < prefix.breakStarts.size(); i++) {
            long restart = prefix.breakStarts.get(i);
            List<LabEvent> resyncEvents = new ArrayList<>();
            Walk resync = walk(capture, params, bs, csumVersion, resyncEvents, true, restart, prefix.breakMaxEnd);
            if (!resync.transactions.isEmpty()) {
                String cid = "candidate-resync-" + (i + 1) + "-jblk" + restart;
                resyncEvents.add(LabEvent.code("RESYNC_CANDIDATE").uncertain().severity(LabEvent.WARN)
                        .message("a fully verified suffix resumes here; legal but not uniquely decided")
                        .coord("at", Units.U_JRNL_BLOCK, restart)
                        .attr("transactions", resync.transactions.size()).build());
                relabel(resync.transactions, cid);
                candidates.add(new ScanCandidate(cid, false, false,
                        List.copyOf(resync.transactions), List.copyOf(resync.walk),
                        List.copyOf(resyncEvents)));
            }
        }

        String canonical = "scan:" + capture.name() + "|" + capture.ringMap().hashCode() + "|"
                + blockHash(capture) + "|" + params + "|" + RULE_VERSION;
        String resultId = Ids.derived("scan", canonical);

        return new ScanResult(capture.name(), "params@" + params.crashAtJournalBlock(),
                RULE_VERSION, resultId, capture.blockSizeRaw(), declared, bs,
                capture.journalStart(), capture.ringSlots(), csumVersion,
                params.crashAtJournalBlock(), List.copyOf(candidates),
                List.copyOf(events), List.copyOf(diagnostics));
    }

    private static String blockHash(Capture capture) {
        StringBuilder sb = new StringBuilder();
        for (RawBlock b : capture.blocks()) {
            sb.append(b.physicalRingIndex()).append(':').append(b.sha256()).append(';');
        }
        return Ids.sha256Hex(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static long sbIndex(Capture capture) {
        String s = capture.superblockSource();
        if (s != null && s.startsWith("journal-superblock@")) {
            try {
                return Long.parseLong(s.substring(s.indexOf('@') + 1));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return capture.journalStart() - 1;
    }

    private static int parseChecksumVersion(Capture capture, List<LabEvent> events) {
        String raw = capture.checksumVersionRaw();
        try {
            int v = Integer.parseInt(raw.trim());
            if (v >= 0 && v <= 2) {
                events.add(LabEvent.code("CHECKSUM_VERSION_OK")
                        .attr("version", v).attr("raw", raw).build());
                return v;
            }
        } catch (NumberFormatException ignored) {
            // handled below
        }
        events.add(LabEvent.code("CHECKSUM_VERSION_UNKNOWN").severity(LabEvent.WARN)
                .attr("raw", raw).attr("assumed", 0).build());
        return 0;
    }

    private static void relabel(List<TransactionGroup> txns, String cid) {
        for (int i = 0; i < txns.size(); i++) {
            TransactionGroup t = txns.get(i);
            txns.set(i, new TransactionGroup(t.groupId(), t.sequence(), t.descriptorRingIndex(),
                    t.revokeRingIndex(), t.commitRingIndex(), t.tags(), t.revokedBlocks(),
                    TransactionGroup.RESYNC, t.evidence(), cid));
        }
    }

    private static final class Walk {
        final List<ScanBlock> walk = new ArrayList<>();
        final List<TransactionGroup> transactions = new ArrayList<>();
        final List<LabEvent> events = new ArrayList<>();
        final List<Long> breakStarts = new ArrayList<>();
        Long breakMaxEnd;
    }

    private Walk walk(Capture capture, ParameterSet params, int bs, int csumVersion,
                      List<LabEvent> sink, boolean resync, long startAt, Long stopAtExclusive) {
        Walk w = new Walk();
        Map<Long, RawBlock> ring = capture.ringMap();
        long start = capture.journalStart();
        long slots = capture.ringSlots();
        long crash = params.crashAtJournalBlock();

        long cursor = startAt;
        int visited = 0;
        Long expectedSeq = null;
        Assembler asm = null;

        while (visited < Math.min(slots + 1, MAX_WALK_SLOTS)) {
            long ringIdx = start + Math.floorMod(cursor - start, slots);
            long physicalAbsolute = cursor;
            boolean wrapped = cursor >= start + slots;
            boolean beforeCrash = physicalAbsolute < crash;
            RawBlock block = ring.get(ringIdx);
            visited++;
            if (block == null) {
                w.walk.add(new ScanBlock(w.walk.size(), ringIdx, wrapped, beforeCrash,
                        ScanBlock.UNRECOGNISED, null, CheckVerdict.NOT_PRESENT,
                        "empty-slot-walk-ends"));
                if (asm != null) {
                    finishAtBoundary(w, asm, resync, "ring walk ended at an empty slot");
                    asm = null;
                }
                break;
            }
            if (stopAtExclusive != null && physicalAbsolute >= stopAtExclusive) {
                break;
            }
            if (!beforeCrash) {
                w.walk.add(new ScanBlock(w.walk.size(), ringIdx, wrapped, false,
                        classify(block.bytes(), bs)[0], classify(block.bytes(), bs)[1],
                        CheckVerdict.NOT_APPLICABLE, "at-or-after-crash-boundary"));
                if (asm != null) {
                    halfAtCrash(w, asm);
                    asm = null;
                }
                break;
            }
            if (visited > slots) {
                w.events.add(LabEvent.code("RING_FULL_TRAVERSED").severity(LabEvent.WARN)
                        .message("populated ring shows no empty slot; stopped after one full revolution")
                        .build());
                if (asm != null) {
                    finishAtBoundary(w, asm, resync, "full ring revolution");
                    asm = null;
                }
                break;
            }

            byte[] bytes = block.bytes();
            int[] typeSeq = classify(bytes, bs);
            int type = typeSeq[0];
            Long seq = typeSeq[1] == -1L ? null : typeSeq[1];
            CheckVerdict verdict = checkBlock(type, bytes, bs, csumVersion, seq, asm, sink);
            String note = "visited";

            switch (type) {
                case Jbd2.DESCRIPTOR_BLOCK -> {
                    if (asm != null) {
                        if (!asm.committed) {
                            finishAtBoundary(w, asm, resync, "new descriptor before commit");
                        }
                    }
                    Descriptor d = Descriptor.parse(bytes, csumVersion);
                    if (verdict == CheckVerdict.BAD) {
                        note = "descriptor-checksum-bad";
                        handleBreak(w, asm, ringIdx, "DESCRIPTOR_CSUM_BAD", resync);
                        asm = null;
                        long next = physicalAbsolute + 1;
                        if (!w.breakStarts.contains(next)) {
                            w.breakStarts.add(next);
                            w.breakMaxEnd = crash;
                        }
                    } else {
                        asm = beginAssembly(w, d, ringIdx, expectedSeq, wrapped);
                        expectedSeq = CircularSeq.next(d.sequence());
                    }
                }
                case Jbd2.REVOKE_BLOCK -> {
                    if (verdict == CheckVerdict.BAD) {
                        handleBreak(w, asm, ringIdx, "REVOKE_CSUM_BAD", resync);
                        asm = null;
                        long next = physicalAbsolute + 1;
                        if (!w.breakStarts.contains(next)) {
                            w.breakStarts.add(next);
                            w.breakMaxEnd = crash;
                        }
                    } else if (asm != null) {
                        Revoke r = Revoke.parse(bytes, csumVersion);
                        asm.revokeRingIndex = ringIdx;
                        asm.revoked.addAll(r.blocks());
                        asm.evidence.add("revoke@" + ringIdx + " count=" + r.blocks().size());
                        w.events.add(LabEvent.code("REVOKE_BLOCK")
                                .coord("at", Units.U_JRNL_BLOCK, ringIdx)
                                .attr("sequence", seq).attr("count", r.blocks().size())
                                .attr("sequenceUnit", Units.U_SEQ32).build());
                    } else {
                        note = "revoke-without-open-transaction";
                        w.events.add(LabEvent.code("REVOKE_WITHOUT_DESCRIPTOR").severity(LabEvent.WARN)
                                .coord("at", Units.U_JRNL_BLOCK, ringIdx).build());
                    }
                }
                case Jbd2.COMMIT_BLOCK -> {
                    if (asm == null) {
                        note = "commit-without-descriptor";
                        w.events.add(LabEvent.code("COMMIT_WITHOUT_DESCRIPTOR").severity(LabEvent.WARN)
                                .coord("at", Units.U_JRNL_BLOCK, ringIdx).build());
                    } else if (verdict == CheckVerdict.BAD) {
                        note = "commit-checksum-bad";
                        handleBreak(w, asm, ringIdx, "COMMIT_CSUM_BAD", resync);
                        asm = null;
                    } else {
                        Commit c = Commit.parse(bytes, csumVersion);
                        completeAssembly(w, asm, ringIdx, c);
                        asm.committed = true;
                        asm = null;
                    }
                }
                default -> {
                    if (asm == null) {
                        note = "plain-data-outside-transaction";
                    } else {
                        note = consumeData(w, asm, bytes, ringIdx, csumVersion, verdict);
                    }
                }
            }
            w.walk.add(new ScanBlock(w.walk.size(), ringIdx, wrapped, beforeCrash, type, seq, verdict, note));
            cursor++;
        }
        if (asm != null && !asm.committed) {
            finishAtBoundary(w, asm, resync, "walk ended");
        }
        sink.addAll(w.events);
        return w;
    }

    private static int[] classify(byte[] b, int bs) {
        if (b == null || b.length < Jbd2.HEADER_SIZE) {
            return new int[]{ScanBlock.UNRECOGNISED, -1};
        }
        long magic = Be.u32(b, 0);
        if (magic != Jbd2.SUPERBLOCK_MAGIC) {
            return new int[]{ScanBlock.PLAIN_DATA, -1};
        }
        int type = (int) Be.u32(b, 4);
        long seq = Be.u32(b, 8);
        if (type == Jbd2.DESCRIPTOR_BLOCK || type == Jbd2.COMMIT_BLOCK
                || type == Jbd2.REVOKE_BLOCK || type == Jbd2.SUPERBLOCK_V2
                || type == Jbd2.SUPERBLOCK_V1) {
            return new int[]{type, (int) seq};
        }
        return new int[]{ScanBlock.UNRECOGNISED, -1};
    }
