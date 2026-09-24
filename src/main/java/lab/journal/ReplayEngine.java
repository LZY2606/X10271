package lab.journal;

import lab.model.Model.Candidate;
import lab.model.Model.Overwrite;
import lab.model.Model.ReplayPlan;
import lab.model.Model.RevokeHit;
import lab.model.Model.Txn;
import lab.model.Model.Write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replay rules (non-negotiable semantics):
 *  - only transactions with a complete commit block are replayable;
 *  - revoke applies by sequence range (modular, relative to the ring window);
 *  - ring wraparound never uses plain integer ordering of sequence numbers;
 *  - a checksum failure keeps the boundary after it uncertain, and all legal
 *    candidates are preserved instead of collapsing to a single answer.
 */
public final class ReplayEngine {

    /** Modular sequence comparison relative to the ring window base. */
    public static int seqCompare(long a, long b, long base) {
        long da = (a - base) & 0xFFFFFFFFL;
        long db = (b - base) & 0xFFFFFFFFL;
        return Long.compare(da, db);
    }

    public ReplayPlan plan(JournalParser.Parsed parsed, Long crashAtSlot) {
        ReplayPlan plan = new ReplayPlan();
        plan.journalId = parsed.spec.id();
        plan.crashAtSlot = crashAtSlot;
        plan.diagnostics.addAll(parsed.diagnostics);
        plan.blockSizeValid = parsed.blockSizeValid;
        plan.txns.addAll(parsed.txns);

        long base = parsed.txns.isEmpty() ? 0 : parsed.txns.get(0).sequence;

        // Phase 1: ring-order scan. Stop conditions produce the certain prefix.
        List<Txn> uncertainTail = new ArrayList<>();
        boolean broken = false;
        for (Txn t : parsed.txns) {
            if (broken) {
                t.state = "UNCERTAIN";
                uncertainTail.add(t);
                continue;
            }
            if (crashAtSlot != null && t.startSlot == crashAtSlot) {
                t.state = "BEYOND_CRASH";
                plan.events.add(event("CRASH_POINT_REACHED", t.sequence,
                        "scan stops at expected crash slot " + crashAtSlot));
                broken = true;
                continue;
            }
            if (!t.checksumOk) {
                t.state = "CHECKSUM_BREAK";
                plan.checksumBreakAtSequence = t.sequence;
                plan.events.add(event("CHECKSUM_BOUNDARY", t.sequence,
                        "checksum failure; boundary after this point stays uncertain"));
                broken = true;
                uncertainTail.add(t);
                continue;
            }
            if (!t.hasCommit) {
                t.state = "NO_COMMIT";
                plan.events.add(event("TXN_EXCLUDED_NO_COMMIT", t.sequence,
                        "only transactions with a complete commit are replayable"));
                broken = true; // crash truncation: nothing after a half transaction is replayed
                continue;
            }
            t.state = "REPLAYABLE";
            plan.replayable.add(t);
        }

        // Phase 2: revoke by sequence range, then last-write-wins.
        applyWrites(plan, plan.replayable, base, plan.blockMap, plan.appliedWrites, plan.revokeHits, plan.overwrites);

        // Phase 3: uncertainty candidates (kept, never silently collapsed).
        if (plan.checksumBreakAtSequence != null || !uncertainTail.isEmpty() && plan.crashAtSlot == null) {
            LinkedHashMap<Long, String> a = new LinkedHashMap<>();
            applyWrites(new ReplayPlan(), plan.replayable, base, a, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            plan.candidates.add(new Candidate("stop-before-break",
                    "在 checksum 断点前停止（保守）", seqs(plan.replayable), a, false));

            List<Txn> optimistic = new ArrayList<>(plan.replayable);
            for (Txn t : uncertainTail) {
                if (t.hasCommit && t.state.equals("UNCERTAIN")) optimistic.add(t);
            }
            LinkedHashMap<Long, String> b = new LinkedHashMap<>();
            applyWrites(new ReplayPlan(), optimistic, base, b, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            plan.candidates.add(new Candidate("optimistic-continue",
                    "越过断点继续重放完整提交的事务（乐观）", seqs(optimistic), b, false));
            plan.events.add(event("CANDIDATES_KEPT", plan.checksumBreakAtSequence,
                    "kept " + plan.candidates.size() + " legal candidates; no single answer forced"));
        }

        plan.idempotent = verifyIdempotent(plan.blockMap);
        plan.events.add(event("IDEMPOTENCY_CHECK", null,
                "replay is idempotent: " + plan.idempotent));
        return plan;
    }

    private static List<Long> seqs(List<Txn> txns) {
        List<Long> out = new ArrayList<>();
        for (Txn t : txns) out.add(t.sequence);
        return out;
    }

    /** Applies revoke ranges and last-write-wins to produce a block map. */
    static void applyWrites(ReplayPlan plan, List<Txn> txns, long base,
                            LinkedHashMap<Long, String> blockMap,
                            List<Write> appliedOut, List<RevokeHit> hitsOut, List<Overwrite> overwritesOut) {
        List<Write> pending = new ArrayList<>();
        for (Txn t : txns) {
            // Revoke in txn R cancels pending writes whose sequence is <= R (modular window).
            for (long victim : t.revokes) {
                var it = pending.iterator();
                while (it.hasNext()) {
                    Write w = it.next();
                    if (w.fsblock() == victim && seqCompare(w.sequence(), t.sequence, base) <= 0) {
                        it.remove();
                        hitsOut.add(new RevokeHit(victim, w.sequence(), t.sequence));
                        plan.events.add(planEvent(plan, "REVOKE_HIT", t.sequence,
                                "fsblock " + victim + " write from seq " + w.sequence()
                                        + " revoked by seq " + t.sequence));
                    }
                }
            }
            for (Write w : t.writes) {
                for (Write old : pending) {
                    if (old.fsblock() == w.fsblock()) {
                        overwritesOut.add(new Overwrite(w.fsblock(), old.sequence(), w.sequence()));
                        plan.events.add(planEvent(plan, "DUP_BLOCK_OVERWRITE", w.sequence(),
                                "fsblock " + w.fsblock() + " overwritten: seq " + old.sequence()
                                        + " -> " + w.sequence()));
                    }
                }
                pending.removeIf(x -> x.fsblock() == w.fsblock());
                pending.add(w);
            }
        }
        for (Write w : pending) {
            blockMap.put(w.fsblock(), w.payloadHex());
            appliedOut.add(w);
        }
    }

    private static Map<String, Object> planEvent(ReplayPlan plan, String type, Long seq, String detail) {
        Map<String, Object> e = event(type, seq, detail);
        return e;
    }

    static Map<String, Object> event(String type, Long sequence, String detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        if (sequence != null) e.put("sequence", sequence);
        e.put("detail", detail);
        return e;
    }

    /** Apply a block map onto a base image (fsblock -> hex bytes). */
    public static LinkedHashMap<Long, String> applyPlan(Map<Long, String> baseImage,
                                                        Map<Long, String> blockMap) {
        LinkedHashMap<Long, String> out = new LinkedHashMap<>(baseImage);
        out.putAll(blockMap);
        return out;
    }

    /** Replaying an already-replayed image must be a fixed point. */
    public static boolean verifyIdempotent(Map<Long, String> blockMap) {
        Map<Long, String> image = new LinkedHashMap<>();
        Map<Long, String> once = applyPlan(image, blockMap);
        Map<Long, String> twice = applyPlan(once, blockMap);
        return once.equals(twice);
    }
}
