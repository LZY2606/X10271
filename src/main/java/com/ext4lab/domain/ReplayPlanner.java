package com.ext4lab.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an explainable replay plan from a scan:
 * - only committed transactions contribute writes;
 * - a revoke at sequence R cancels writes from transactions with seq &lt;= R (ring comparison);
 *   a rewrite at seq &gt; R survives (rewrite-after-revoke);
 * - duplicate writes to one fs block: the ring-latest wins, earlier ones become SUPERSEDED;
 * - when the scan ended at a checksum boundary, a second "trust-tail" candidate is kept
 *   with every post-boundary write marked UNCERTAIN. No candidate is ever dropped.
 */
public final class ReplayPlanner {
    private ReplayPlanner() {}

    public static Model.ReplayPlan plan(Model.ScanResult scan) {
        List<Model.Txn> committed = new ArrayList<>();
        for (Model.Txn t : scan.txns()) {
            if (t.committed()) committed.add(t);
        }
        committed.sort((a, b) -> SeqNo.compare(a.sequence(), b.sequence()));

        List<Model.Overwrite> ordered = new ArrayList<>();
        List<Model.RevokeHit> hits = new ArrayList<>();
        for (Model.Txn t : committed) {
            for (Model.DataEntry e : t.data()) {
                Model.Revoke killer = latestRevokeCovering(scan.revokes(), e.fsBlock(), t.sequence());
                if (killer != null) {
                    ordered.add(new Model.Overwrite(e.fsBlock(), e.ringIndex(), t.sequence(),
                            Model.Status.REVOKED,
                            "revoked by " + killer.id() + " seq=" + SeqNo.hex(killer.sequence())));
                    hits.add(new Model.RevokeHit(e.fsBlock(), killer.sequence(), t.sequence(),
                            killer.ringIndex()));
                } else {
                    ordered.add(new Model.Overwrite(e.fsBlock(), e.ringIndex(), t.sequence(),
                            Model.Status.CERTAIN, "committed in " + t.id()));
                }
            }
        }

        Map<Long, Integer> lastLiveIdx = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Model.Overwrite w = ordered.get(i);
            if (w.status() != Model.Status.REVOKED) {
                lastLiveIdx.put(w.fsBlock(), i);
            }
        }
        List<Model.Overwrite> orderedFinal = new ArrayList<>();
        Map<Long, Model.Overwrite> finalMap = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Model.Overwrite w = ordered.get(i);
            if (w.status() == Model.Status.REVOKED) {
                orderedFinal.add(w);
            } else if (lastLiveIdx.get(w.fsBlock()) == i) {
                orderedFinal.add(w);
                finalMap.put(w.fsBlock(), w);
            } else {
                Model.Overwrite winner = ordered.get(lastLiveIdx.get(w.fsBlock()));
                orderedFinal.add(new Model.Overwrite(w.fsBlock(), w.journalRingIndex(), w.txnSeq(),
                        Model.Status.SUPERSEDED,
                        "superseded by later write JB#" + winner.journalRingIndex()
                                + " in TXN#" + SeqNo.hex(winner.txnSeq())));
            }
        }

        List<Model.Candidate> candidates = new ArrayList<>();
        candidates.add(new Model.Candidate("stop-at-boundary", finalMap, true,
                scan.uncertaintyBoundary() != null
                        ? "conservative: replay stops at checksum boundary ring block "
                            + scan.uncertaintyBoundary()
                        : "all durable committed transactions replayed"));

        if (scan.uncertaintyBoundary() != null) {
            Model.ScanResult optimistic = JournalScanner.scan(scan.superblock(), scan.ring(),
                    scan.crashIndex(), true);
            Model.ReplayPlan tail = planWithoutCandidates(optimistic);
            Map<Long, Model.Overwrite> tailMap = new LinkedHashMap<>();
            for (Model.Candidate c : tail.candidates()) {
                for (Map.Entry<Long, Model.Overwrite> en : c.map().entrySet()) {
                    Model.Overwrite w = en.getValue();
                    if (w.journalRingIndex() >= scan.uncertaintyBoundary()) {
                        w = new Model.Overwrite(w.fsBlock(), w.journalRingIndex(), w.txnSeq(),
                                Model.Status.UNCERTAIN,
                                "beyond checksum boundary at ring block " + scan.uncertaintyBoundary());
                    }
                    tailMap.put(en.getKey(), w);
                }
            }
            candidates.add(new Model.Candidate("trust-tail", tailMap, false,
                    "optimistic: ignore checksum failure and replay the tail; kept as unchosen candidate"));
        }
        return new Model.ReplayPlan(orderedFinal, hits, candidates, scan.anomalies());
    }

    private static Model.ReplayPlan planWithoutCandidates(Model.ScanResult scan) {
        Model.ScanResult noBoundary = new Model.ScanResult(scan.superblock(), scan.ring(),
                scan.crashIndex(), scan.txns(), scan.revokes(), scan.anomalies(), null);
        return plan(noBoundary);
    }

    static Model.Revoke latestRevokeCovering(List<Model.Revoke> revokes, long fsBlock, long txnSeq) {
        Model.Revoke killer = null;
        for (Model.Revoke r : revokes) {
            if (r.fsBlocks().contains(fsBlock) && SeqNo.compare(r.sequence(), txnSeq) >= 0) {
                if (killer == null || SeqNo.after(r.sequence(), killer.sequence())) {
                    killer = r;
                }
            }
        }
        return killer;
    }

    /** Applies a candidate map onto a base filesystem image. Pure and deterministic. */
    public static Map<Long, byte[]> apply(Map<Long, byte[]> base, Model.Candidate candidate,
                                          Map<Integer, byte[]> ringBytes) {
        Map<Long, byte[]> out = new HashMap<>(base);
        for (Map.Entry<Long, Model.Overwrite> en : candidate.map().entrySet()) {
            Model.Overwrite w = en.getValue();
            if (w.status() == Model.Status.CERTAIN || w.status() == Model.Status.UNCERTAIN) {
                byte[] bytes = ringBytes.get(w.journalRingIndex());
                if (bytes != null) out.put(en.getKey(), bytes);
            }
        }
        return out;
    }
}
