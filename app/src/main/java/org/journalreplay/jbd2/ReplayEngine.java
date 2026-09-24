package org.journalreplay.jbd2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scans a parsed journal up to a crash point and derives an explainable replay
 * plan. Core semantics, all emitted as machine-readable judgments:
 * <ul>
 *   <li>only transactions with a complete, checksum-valid commit replay;</li>
 *   <li>revoke records cancel writes to the same fs block in transactions whose
 *       sequence is equal-or-earlier on the ring (modular, never plain int sort);</li>
 *   <li>a failed commit checksum makes everything from that transaction onward
 *       uncertain: deterministic replay stops there and all legal candidate
 *       outcomes are preserved side by side;</li>
 *   <li>replay is idempotent: applying the resulting block map twice changes
 *       nothing the second time.</li>
 * </ul>
 */
public final class ReplayEngine {

    public enum TxStatus { COMMITTED, HALF, UNCERTAIN }
    public enum CoverageStatus { REPLAYED, REVOKED, SUPERSEDED, SKIPPED_HALF_TX, UNCERTAIN }

    public record Write(long fsBlock, long txSeq, long ringIndex, byte[] data) {}
    public record TxGroup(long sequence, TxStatus status, List<JournalBlock> blocks,
                          List<Long> revokedBlocks, String stableId) {}
    public record Coverage(long fsBlock, long txSeq, long ringIndex,
                           CoverageStatus status, String reason, String stableId) {}
    public record Judgment(String id, String kind, String summary, Map<String, Object> detail) {}
    public record CandidateMap(String id, String label, Map<Long, String> blockValues,
                               String rationale) {}
    public record ReplayPlan(List<TxGroup> transactions, List<Coverage> coverage,
                             Map<Long, byte[]> blockMap, List<CandidateMap> candidates,
                             List<Judgment> judgments, int crashPoint,
                             List<String> diagnostics) {}

    private final ChecksumVersion version;
    private final long uuidCrc;

    public ReplayEngine(ChecksumVersion version, byte[] uuid) {
        this.version = version;
        this.uuidCrc = uuid == null ? 0 : Checksums.crc32c(uuid, 0, uuid.length);
    }

    public ReplayPlan plan(JournalParser.ParsedJournal journal, int crashPoint) {
        List<Judgment> judgments = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int[] jseq = {0};
        java.util.function.BiFunction<String, String, Judgment> judge = (kind, summary) -> {
            String id = String.format("jdg-%02d", ++jseq[0]);
            Judgment j = new Judgment(id, kind, summary, new LinkedHashMap<>());
            judgments.add(j);
            return j;
        };

        List<JournalBlock> blocks = journal.blocks();
        int limit = Math.min(crashPoint, blocks.size());

        // --- Phase 1: group blocks into transactions -----------------------
        List<TxGroup> txs = new ArrayList<>();
        List<JournalBlock> open = null;
        long openSeq = -1;
        List<Long> openRevoked = new ArrayList<>();
        boolean uncertainTail = false;
        for (int i = 0; i < limit; i++) {
            JournalBlock b = blocks.get(i);
            switch (b.type()) {
                case SUPERBLOCK_V1, SUPERBLOCK_V2 -> { /* journal geometry, not a tx */ }
                case DESCRIPTOR -> {
                    if (open != null) {
                        Judgment j = judge.apply("half-transaction",
                                "transaction seq=" + openSeq + " has no commit before next descriptor");
                        j.detail().put("sequence", openSeq);
                        j.detail().put("firstRingIndex", open.get(0).ringIndex());
                        txs.add(new TxGroup(openSeq, TxStatus.HALF, List.copyOf(open),
                                List.copyOf(openRevoked), "tx-" + openSeq));
                    }
                    open = new ArrayList<>();
                    open.add(b);
                    openSeq = b.sequence();
                    openRevoked = new ArrayList<>();
                }
                case DATA -> {
                    if (open != null) open.add(b);
                }
                case REVOKE -> {
                    if (open != null && b.sequence() == openSeq) {
                        open.add(b);
                        openRevoked.addAll(b.revoked());
                    } else if (open != null) {
                        open.add(b);
                        openRevoked.addAll(b.revoked());
                    }
                }
                case COMMIT -> {
                    if (open != null) {
                        open.add(b);
                        TxStatus status;
                        if (uncertainTail) {
                            status = TxStatus.UNCERTAIN;
                        } else {
                            boolean ok = verifyCommit(open, journal);
                            if (ok) {
                                status = TxStatus.COMMITTED;
                                Judgment j = judge.apply("commit-complete",
                                        "transaction seq=" + openSeq + " has complete valid commit");
                                j.detail().put("sequence", openSeq);
                                j.detail().put("checksumVersion", version.code);
                            } else {
                                status = TxStatus.UNCERTAIN;
                                uncertainTail = true;
                                Judgment j = judge.apply("checksum-boundary",
                                        "commit checksum failed at seq=" + openSeq
                                                + "; boundary after this point stays uncertain");
                                j.detail().put("sequence", openSeq);
                                j.detail().put("checksumVersion", version.code);
                                j.detail().put("commitRingIndex", b.ringIndex());
                            }
                        }
                        txs.add(new TxGroup(openSeq, status, List.copyOf(open),
                                List.copyOf(openRevoked), "tx-" + openSeq));
                        open = null;
                    }
                }
            }
        }
        if (open != null) {
            Judgment j = judge.apply("half-transaction",
                    "transaction seq=" + openSeq + " cut by crash point " + crashPoint);
            j.detail().put("sequence", openSeq);
            j.detail().put("crashPoint", crashPoint);
            txs.add(new TxGroup(openSeq, TxStatus.HALF, List.copyOf(open),
                    List.copyOf(openRevoked), "tx-" + openSeq));
        }

        // --- Phase 2: ring-order assertion ---------------------------------
        for (int i = 1; i < txs.size(); i++) {
            long prev = txs.get(i - 1).sequence();
            long cur = txs.get(i).sequence();
            if (SeqCompare.before(cur, prev)) {
                diagnostics.add("sequence order violation beyond wrap window: " + prev + " -> " + cur);
            }
            if (cur < prev) { // plain integers would misorder; ring order is authoritative
                Judgment j = judge.apply("ring-wraparound",
                        "sequence wrapped " + prev + " -> " + cur
                                + "; modular ring order used, not integer sort");
                j.detail().put("before", prev);
                j.detail().put("after", cur);
            }
        }

        // --- Phase 3: collect writes and revokes from committed txs --------
        record RevokeHit(long fsBlock, long revokeTxSeq) {}
        List<Write> writes = new ArrayList<>();
        List<RevokeHit> revokes = new ArrayList<>();
        Map<Long, List<Write>> writesByBlock = new LinkedHashMap<>();
        for (TxGroup tx : txs) {
            if (tx.status() != TxStatus.COMMITTED) continue;
            List<Long> tags = null;
            List<JournalBlock> dataBlocks = new ArrayList<>();
            for (JournalBlock b : tx.blocks()) {
                if (b.type() == BlockType.DESCRIPTOR) tags = b.fsBlocks();
                if (b.type() == BlockType.DATA) dataBlocks.add(b);
            }
            if (tags != null) {
                for (int i = 0; i < tags.size() && i < dataBlocks.size(); i++) {
                    Write w = new Write(tags.get(i), tx.sequence(),
                            dataBlocks.get(i).ringIndex(), dataBlocks.get(i).data());
                    writes.add(w);
                    writesByBlock.computeIfAbsent(w.fsBlock(), k -> new ArrayList<>()).add(w);
                }
            }
            for (Long r : tx.revokedBlocks()) revokes.add(new RevokeHit(r, tx.sequence()));
        }

        // --- Phase 4: coverage order with revoke + supersede ---------------
        List<Coverage> coverage = new ArrayList<>();
        Map<Long, byte[]> map = new TreeMap<>();
        int[] cseq = {0};
        // half-tx writes recorded as skipped
        for (TxGroup tx : txs) {
            if (tx.status() == TxStatus.HALF) {
                List<Long> tags = null;
                List<JournalBlock> dataBlocks = new ArrayList<>();
                for (JournalBlock b : tx.blocks()) {
                    if (b.type() == BlockType.DESCRIPTOR) tags = b.fsBlocks();
                    if (b.type() == BlockType.DATA) dataBlocks.add(b);
                }
                if (tags != null) {
                    for (int i = 0; i < tags.size() && i < dataBlocks.size(); i++) {
                        coverage.add(new Coverage(tags.get(i), tx.sequence(),
                                dataBlocks.get(i).ringIndex(), CoverageStatus.SKIPPED_HALF_TX,
                                "transaction has no complete commit", "cov-" + (++cseq[0])));
                    }
                }
            }
        }
        for (Write w : writes) {
            // revoke: cancelled iff a revoke for the same block exists in a committed
            // transaction whose sequence is equal-or-later on the ring
            Long revokedBy = null;
            for (RevokeHit r : revokes) {
                if (r.fsBlock() == w.fsBlock() && SeqCompare.geq(r.revokeTxSeq(), w.txSeq())) {
                    revokedBy = r.revokeTxSeq();
                }
            }
            List<Write> all = writesByBlock.get(w.fsBlock());
            Write last = all.get(all.size() - 1);
            boolean superseded = last != w;
            CoverageStatus st;
            String reason;
            if (revokedBy != null && !(superseded && SeqCompare.before(revokedBy, last.txSeq()))) {
                st = CoverageStatus.REVOKED;
                reason = "revoked by transaction seq=" + revokedBy + " (ring range)";
                Judgment j = judge.apply("revoke-hit",
                        "fs block " + w.fsBlock() + " write from seq=" + w.txSeq()
                                + " revoked by seq=" + revokedBy);
                j.detail().put("fsBlock", w.fsBlock());
                j.detail().put("writeSeq", w.txSeq());
                j.detail().put("revokeSeq", revokedBy);
            } else if (superseded) {
                st = CoverageStatus.SUPERSEDED;
                reason = "later committed write at ring index " + last.ringIndex();
            } else {
                st = CoverageStatus.REPLAYED;
                reason = "last committed writer in ring order";
                map.put(w.fsBlock(), w.data());
            }
            coverage.add(new Coverage(w.fsBlock(), w.txSeq(), w.ringIndex(), st, reason,
                    "cov-" + (++cseq[0])));
        }

        // --- Phase 5: uncertainty candidates --------------------------------
        List<CandidateMap> candidates = new ArrayList<>();
        TxGroup firstUncertain = txs.stream().filter(t -> t.status() == TxStatus.UNCERTAIN)
                .findFirst().orElse(null);
        if (firstUncertain != null) {
            Map<Long, String> exclude = new TreeMap<>();
            map.forEach((k, v) -> exclude.put(k, preview(v)));
            candidates.add(new CandidateMap("cand-exclude-uncertain",
                    "exclude checksum-failed transaction",
                    exclude, "deterministic replay stops before seq=" + firstUncertain.sequence()));

            Map<Long, byte[]> includeAll = new TreeMap<>(map);
            for (TxGroup tx : txs) {
                if (tx.status() != TxStatus.UNCERTAIN) continue;
                List<Long> tags = null;
                List<JournalBlock> dataBlocks = new ArrayList<>();
                for (JournalBlock b : tx.blocks()) {
                    if (b.type() == BlockType.DESCRIPTOR) tags = b.fsBlocks();
                    if (b.type() == BlockType.DATA) dataBlocks.add(b);
                }
                if (tags != null) {
                    for (int i = 0; i < tags.size() && i < dataBlocks.size(); i++) {
                        long fs = tags.get(i);
                        boolean revoked = false;
                        for (RevokeHit r : revokes) {
                            if (r.fsBlock() == fs && SeqCompare.geq(r.revokeTxSeq(), tx.sequence())) {
                                revoked = true;
                            }
                        }
                        if (!revoked) includeAll.put(fs, dataBlocks.get(i).data());
                    }
                }
            }
            Map<Long, String> include = new TreeMap<>();
            includeAll.forEach((k, v) -> include.put(k, preview(v)));
            candidates.add(new CandidateMap("cand-include-uncertain",
                    "include checksum-failed transaction blocks",
                    include, "data blocks may be intact; checksum boundary keeps this legal"));
            Judgment j = judge.apply("uncertainty-preserved",
                    candidates.size() + " legal candidate block maps retained; none collapsed");
            j.detail().put("candidates", candidates.stream().map(CandidateMap::id).toList());
        }

        return new ReplayPlan(txs, coverage, map, candidates, judgments, crashPoint, diagnostics);
    }

    /** Applies a block map to a base image. Pure: replay(replay(x)) == replay(x). */
    public static byte[] apply(byte[] baseImage, int blockSize, Map<Long, byte[]> blockMap) {
        byte[] out = baseImage.clone();
        for (Map.Entry<Long, byte[]> e : blockMap.entrySet()) {
            int off = (int) (e.getKey() * blockSize);
            if (off + blockSize <= out.length) {
                System.arraycopy(e.getValue(), 0, out, off, blockSize);
            }
        }
        return out;
    }

    /**
     * Verifies a transaction's commit record. Content is the concatenation of
     * the raw descriptor block and raw data blocks in ring order. v1 stores a
     * crc16 in the low 2 bytes of the 4-byte field at offset 16; v2 stores
     * crc32c(seed=0); v3 stores crc32c seeded with the uuid checksum and
     * additionally requires every descriptor tag checksum to match
     * crc32c(uuidCrc, fsBlockBE || dataBlock).
     */
    private boolean verifyCommit(List<JournalBlock> txBlocks, JournalParser.ParsedJournal journal) {
        JournalBlock commit = null;
        JournalBlock descriptor = null;
        List<JournalBlock> dataBlocks = new ArrayList<>();
        for (JournalBlock b : txBlocks) {
            if (b.type() == BlockType.COMMIT) commit = b;
            if (b.type() == BlockType.DESCRIPTOR) descriptor = b;
            if (b.type() == BlockType.DATA) dataBlocks.add(b);
        }
        if (commit == null) return false;
        int bs = journal.blockSize();
        byte[] image = journal.image();
        java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
        for (JournalBlock b : txBlocks) {
            if (b.type() == BlockType.DESCRIPTOR || b.type() == BlockType.DATA) {
                int off = (int) b.ringIndex() * bs;
                content.write(image, off, bs);
            }
        }
        byte[] bytes = content.toByteArray();
        int commitOff = (int) commit.ringIndex() * bs;
        long stored = JournalParser.u32(image, commitOff + 16);
        return switch (version) {
            case V1_CRC16 -> (stored & 0xFFFF) == Checksums.crc16(bytes, 0, bytes.length);
            case V2_CRC32C -> stored == Checksums.crc32c(0, bytes, 0, bytes.length);
            case V3_CRC32C_PER_TAG -> {
                if (stored != Checksums.crc32c(uuidCrc, bytes, 0, bytes.length)) yield false;
                if (descriptor != null) {
                    int descOff = (int) descriptor.ringIndex() * bs;
                    for (int i = 0; i < descriptor.fsBlocks().size() && i < dataBlocks.size(); i++) {
                        long tagCrc = JournalParser.u32(image, descOff + 12 + i * 16 + 4);
                        byte[] fsBe = new byte[4];
                        JournalParser.put32(fsBe, 0, descriptor.fsBlocks().get(i));
                        long seed = Checksums.crc32c(uuidCrc, fsBe, 0, 4);
                        byte[] d = dataBlocks.get(i).data();
                        if (tagCrc != Checksums.crc32c(seed, d, 0, d.length)) yield false;
                    }
                }
                yield true;
            }
        };
    }

    private static String preview(byte[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, v.length); i++) {
            if (v[i] >= 32 && v[i] < 127) sb.append((char) v[i]); else sb.append('.');
        }
        return sb.toString();
    }
}
