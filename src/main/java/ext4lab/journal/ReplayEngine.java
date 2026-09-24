package ext4lab.journal;

import ext4lab.journal.Model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds replay plans from a scan. Rules enforced:
 * - only transactions with a complete commit are replayed;
 * - revoke in txn S cancels writes of the same block from transactions ring-before S;
 * - uncertain (checksum-broken) transactions are never silently included: all legal
 *   candidates are kept for the reviewer;
 * - replay is idempotent: applying a plan to an already-replayed disk is a no-op.
 */
public final class ReplayEngine {

    public ReplayPlan plan(String journalName, ScanResult scan, byte[] journal) {
        List<Write> certainWrites = new ArrayList<>();
        List<Write> uncertainWrites = new ArrayList<>();
        List<Judgment> judgments = new ArrayList<>(scan.judgments());
        List<Anomaly> anomalies = new ArrayList<>(scan.anomalies());
        Superblock sb = scan.sb();
        int bs = sb.usable() ? sb.blockSize() : 0;

        List<String> excluded = new ArrayList<>();
        for (Txn t : scan.txns()) {
            if (t.status() == Txn.Status.COMPLETE) {
                collectWrites(t, journal, bs, certainWrites);
            } else if (t.status() == Txn.Status.UNCERTAIN) {
                collectWrites(t, journal, bs, uncertainWrites);
            } else {
                excluded.add("txn:" + t.seq() + " (" + t.status() + ": " + t.reason() + ")");
            }
        }
        judgments.add(new Judgment("only-committed-txns-replay", journalName,
                excluded.isEmpty() ? "all-scanned-txns-committed" : "excluded:" + String.join(",", excluded),
                "transactions without a verified commit block are never replayed"));

        // revoke applies by sequence range: a revoke in txn S cancels same-block
        // writes from transactions ring-before S (relative to firstSeq, wrap-safe).
        List<RevokeHit> hits = new ArrayList<>();
        List<Write> effective = new ArrayList<>(certainWrites);
        List<Txn> committed = scan.txns().stream()
                .filter(t -> t.status() == Txn.Status.COMPLETE).toList();
        for (Txn t : committed) {
            for (long revokedBlock : t.revokeBlocks()) {
                List<Write> kept = new ArrayList<>();
                for (Write w : effective) {
                    if (w.fsBlock() == revokedBlock
                            && JournalFormat.seqCompare(w.txnSeq(), t.seq(), sb.firstSeq()) < 0) {
                        hits.add(new RevokeHit(revokedBlock, t.seq(), w.txnSeq()));
                        judgments.add(new Judgment("revoke-sequence-range", "block:" + revokedBlock,
                                "cancel write from txn:" + w.txnSeq(),
                                "revoke in txn:" + t.seq() + " covers writes with sequence ring-before "
                                        + t.seq() + " (relative to firstSeq " + sb.firstSeq() + ")"));
                    } else {
                        kept.add(w);
                    }
                }
                effective = kept;
            }
        }

        // duplicate blocks: last write in ring order wins; earlier hits stay visible.
        Map<Long, List<Write>> byBlock = new LinkedHashMap<>();
        for (Write w : effective) byBlock.computeIfAbsent(w.fsBlock(), k -> new ArrayList<>()).add(w);
        for (var e : byBlock.entrySet()) {
            if (e.getValue().size() > 1) {
                judgments.add(new Judgment("duplicate-block-last-wins", "block:" + e.getKey(),
                        "winner txn:" + e.getValue().get(e.getValue().size() - 1).txnSeq(),
                        e.getValue().size() + " writes in ring order; overwrite order preserved"));
            }
            boolean wasRevoked = hits.stream().anyMatch(h -> h.fsBlock() == e.getKey());
            if (wasRevoked && !e.getValue().isEmpty()) {
                judgments.add(new Judgment("revoke-then-rewrite", "block:" + e.getKey(),
                        "final from txn:" + e.getValue().get(e.getValue().size() - 1).txnSeq(),
                        "block revoked, then rewritten by a later committed transaction; rewrite survives"));
            }
        }

        // candidates: C0 = certain prefix only; each uncertain txn adds one candidate.
        List<Candidate> candidates = new ArrayList<>();
        List<Long> certainSeqs = committed.stream().map(Txn::seq).toList();
        candidates.add(new Candidate("C0", "certain prefix (stop before checksum boundary)",
                certainSeqs, true));
        if (!uncertainWrites.isEmpty()) {
            List<Long> seqs = new ArrayList<>(certainSeqs);
            for (Txn t : scan.txns()) {
                if (t.status() == Txn.Status.UNCERTAIN) {
                    seqs.add(t.seq());
                    candidates.add(new Candidate("C" + (candidates.size()),
                            "include unverifiable txn:" + t.seq() + " (checksum failed)",
                            List.copyOf(seqs), false));
                }
            }
            judgments.add(new Judgment("candidates-kept", journalName,
                    candidates.size() + " candidates",
                    "uncertainty is preserved as multiple legal candidates; none is silently dropped"));
        }
        return new ReplayPlan(journalName, certainWrites, hits, effective, candidates,
                judgments, anomalies);
    }

    private void collectWrites(Txn t, byte[] journal, int bs, List<Write> out) {
        List<Tag> tags = t.descriptors().isEmpty() ? List.of() : t.descriptors().get(0).tags();
        for (int i = 0; i < t.dataBlocks().size() && i < tags.size(); i++) {
            JBlock d = t.dataBlocks().get(i);
            out.add(new Write(tags.get(i).fsBlock(), t.seq(), d.ringOffset(),
                    JournalScanner.slice(journal, d.absIndex(), bs)));
        }
    }

    /** Writes selected by a candidate: certain writes plus uncertain ones when included. */
    public List<Write> writesFor(ScanResult scan, ReplayPlan plan, byte[] journal, String candidateId) {
        Candidate c = plan.candidates().stream().filter(x -> x.id().equals(candidateId)).findFirst()
                .orElse(plan.candidates().get(0));
        if (c.certain()) return plan.effectiveWrites();
        Superblock sb = scan.sb();
        List<Write> extra = new ArrayList<>();
        for (Txn t : scan.txns()) {
            if (t.status() == Txn.Status.UNCERTAIN && c.includeTxnSeqs().contains(t.seq())) {
                collectWrites(t, journal, sb.blockSize(), extra);
            }
        }
        List<Write> all = new ArrayList<>(plan.effectiveWrites());
        all.addAll(extra);
        return all;
    }

    /** Applies writes to a disk map (block -> bytes). Pure overlay; idempotent. */
    public Map<Long, byte[]> apply(Map<Long, byte[]> disk, List<Write> writes) {
        Map<Long, byte[]> out = new TreeMap<>(disk);
        for (Write w : writes) out.put(w.fsBlock(), w.bytes());
        return out;
    }

    /** Derived block map at the chosen crash point: one row per touched block. */
    public List<Map<String, Object>> blockMap(Map<Long, byte[]> baseDisk, List<Write> writes,
                                              List<RevokeHit> hits, ScanResult scan) {
        Map<Long, Map<String, Object>> rows = new TreeMap<>();
        for (long b : baseDisk.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("block", b);
            row.put("id", "dm:" + b);
            row.put("source", "original");
            row.put("writes", new ArrayList<>());
            row.put("revoked", hits.stream().anyMatch(h -> h.fsBlock() == b));
            rows.put(b, row);
        }
        for (Write w : writes) {
            Map<String, Object> row = rows.computeIfAbsent(w.fsBlock(), b -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("block", b);
                r.put("id", "dm:" + b);
                r.put("writes", new ArrayList<>());
                r.put("revoked", false);
                return r;
            });
            @SuppressWarnings("unchecked")
            List<String> ws = (List<String>) row.get("writes");
            ws.add("txn:" + w.txnSeq() + "@ring:" + w.dataRingOffset());
            row.put("source", "txn:" + w.txnSeq());
        }
        if (scan.checksumBreakOffset() != null) {
            for (Txn t : scan.txns()) {
                if (t.status() == Txn.Status.UNCERTAIN && t.descriptors() != null && !t.descriptors().isEmpty()) {
                    for (Tag tag : t.descriptors().get(0).tags()) {
                        Map<String, Object> row = rows.get(tag.fsBlock());
                        if (row != null && "original".equals(row.get("source"))) {
                            row.put("source", "uncertain(txn:" + t.seq() + " beyond checksum boundary)");
                        }
                    }
                }
            }
        }
        return new ArrayList<>(rows.values());
    }
}
