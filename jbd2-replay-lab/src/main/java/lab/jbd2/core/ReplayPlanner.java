package lab.jbd2.core;

import lab.jbd2.binary.Jbd2Format;
import lab.jbd2.model.ActionDecision;
import lab.jbd2.model.JournalImage;
import lab.jbd2.model.JournalTxn;
import lab.jbd2.model.RawBlock;
import lab.jbd2.model.ReplayAction;
import lab.jbd2.model.ReplayPlan;
import lab.jbd2.model.RuleEvent;
import lab.jbd2.model.ScanResult;
import lab.jbd2.model.Tag;
import lab.jbd2.model.TxnStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a scan into an explainable replay plan.
 *
 * <ul>
 *   <li>Only transactions with a complete, checksum-valid commit are replayed.</li>
 *   <li>A revoke for filesystem block B with sequence R suppresses writes whose sequence
 *       is in the revoke's range {@code <= R}; later writes are replayed.</li>
 *   <li>Sequence ids are compared with wrap-aware ordering, never plain int sort.</li>
 *   <li>After a checksum failure the replay boundary is uncertain: the conservative plan
 *       stops before it and an alternative boundary is retained as a candidate.</li>
 *   <li>Blocks at or after the requested crash point are not present and excluded.</li>
 * </ul>
 */
public final class ReplayPlanner {

    private static final String RULE_GATE = "jbd2.txn.replayable-only-if-complete-commit";
    private static final String RULE_REVOKE = "jbd2.revoke.takes-effect-by-sequence-range";
    private static final String RULE_ORDER = "jbd2.ring.wrap-aware-sequence-order";
    private static final String RULE_CRASH = "jbd2.replay.crashpoint-excludes-later-blocks";

    /**
     * @param includeAfterCorrupt when false (conservative), stop before the first corrupt
     *                            transaction; when true, assume the corrupt group is the
     *                            crash tail and continue with later valid transactions.
     */
    public ReplayPlan plan(JournalImage image, ScanResult scan, long crashPointPhysical,
                           String branch, boolean includeAfterCorrupt) {
        List<RuleEvent> events = new ArrayList<>();
        List<ReplayAction> actions = new ArrayList<>();
        String uncertainty = null;

        List<JournalTxn> ordered = new ArrayList<>(scan.transactions());
        ordered.sort((a, b) -> Long.compare(a.sequence(), b.sequence()));

        boolean corruptSeen = false;
        String firstCorruptSeq = null;
        Map<Long, Long> revokeAt = new LinkedHashMap<>();

        // Pass 1 (mirrors jbd2 PASS_REVOKE): build the revoke table from transactions
        // eligible for this branch before replaying any data block.
        for (JournalTxn t : ordered) {
            if (t.status() == TxnStatus.CORRUPT) {
                corruptSeen = true;
                if (firstCorruptSeq == null) {
                    firstCorruptSeq = Long.toString(t.sequence() % SequenceRing.MOD);
                }
                if (!includeAfterCorrupt) {
                    break;
                }
                continue;
            }
            if (corruptSeen && !includeAfterCorrupt) {
                break;
            }
            long seq = t.sequence();
            for (Long rb : t.revokeFblocks()) {
                revokeAt.put(rb, seq);
                events.add(RuleEvent.of("revoke", RULE_REVOKE, "info",
                        "Revoke of fs block " + rb + " registered with range <= seq "
                                + (seq % SequenceRing.MOD),
                        "revoke:" + rb + ":" + (seq % SequenceRing.MOD),
                        List.of("txn:" + (seq % SequenceRing.MOD)), seq));
            }
        }

        String uncertainty = null;
        corruptSeen = false;
        for (JournalTxn t : ordered) {
            long seq = t.sequence();
            if (t.status() == TxnStatus.CORRUPT) {
                corruptSeen = true;
                if (firstCorruptSeq == null) {
                    firstCorruptSeq = Long.toString(seq % SequenceRing.MOD);
                }
                uncertainty = "checksum-failure@seq=" + firstCorruptSeq;
                events.add(RuleEvent.of("boundary", "jbd2.checksum.boundary-uncertain-after-failure",
                        "uncertain",
                        "Replay boundary after checksum failure at sequence "
                                + firstCorruptSeq + " is not uniquely decidable",
                        "txn:" + (seq % SequenceRing.MOD),
                        List.of("block:" + t.descriptorPhysical(),
                                "block:" + t.commitPhysical()), seq));
                if (!includeAfterCorrupt) {
                    continue;
                }
            }
            if (corruptSeen && !includeAfterCorrupt) {
                continue;
            }

            if (t.status() != TxnStatus.COMPLETE) {
                events.add(RuleEvent.of("skip", RULE_GATE,
                        t.status() == TxnStatus.INCOMPLETE ? "warn" : "uncertain",
                        "Transaction " + (seq % SequenceRing.MOD)
                                + " is " + t.status() + "; excluded from replay",
                        "txn:" + (seq % SequenceRing.MOD),
                        List.of("block:" + t.descriptorPhysical()), seq));
                continue;
            }
            if (corruptSeen && !includeAfterCorrupt) {
                continue;
            }

            addTxnWrites(image, t, revokeAt, crashPointPhysical, actions, events);
        }

        boolean replayable = actions.stream().anyMatch(a -> a.decision() == ActionDecision.APPLY);
        List<String> candidates = new ArrayList<>();
        if (uncertainty != null) {
            candidates.add("stop-before-" + uncertainty);
            candidates.add("continue-after-" + uncertainty);
        }
        String planId = "plan-" + branch + "-"
                + Integer.toHexString((branch + crashPointPhysical + includeAfterCorrupt).hashCode());
        return new ReplayPlan(planId, branch, replayable,
                "complete-valid-commit;revoke<=seq;wrap-aware-order;crashpoint",
                crashPointPhysical, List.copyOf(actions), List.copyOf(events),
                uncertainty, List.copyOf(candidates));
    }

    private void addTxnWrites(JournalImage image, JournalTxn txn, Map<Long, Long> revokeAt,
                              long crashPoint, List<ReplayAction> actions,
                              List<RuleEvent> events) {
        long seq = txn.sequence();
        Set<Long> seenInTxn = new HashSet<>();
        for (int i = 0; i < txn.tags().size(); i++) {
            Tag tag = txn.tags().get(i);
            long fs = tag.fsBlock();
            long src = tag.dataPhysical();
            String actionId = "act-" + (seq % SequenceRing.MOD) + "-" + fs + "-" + i;

            if (atOrAfterCrash(src, crashPoint, image)) {
                actions.add(new ReplayAction(actionId, seq, fs, src,
                        ActionDecision.AFTER_CRASH_POINT,
                        "data block " + src + " is at/after crash point " + crashPoint, null));
                events.add(RuleEvent.of("crash", RULE_CRASH, "warn",
                        "Write of fs block " + fs + " from journal block " + src
                                + " excluded: at/after crash point",
                        actionId, List.of("src:block:" + src, "param:crashPoint"), seq));
                continue;
            }

            if (!seenInTxn.add(fs)) {
                ReplayAction earlier = lastActionFor(actions, fs);
                if (earlier != null) {
                    actions.set(actions.indexOf(earlier), shadow(earlier));
                }
                actions.add(new ReplayAction(actionId, seq, fs, src, ActionDecision.APPLY,
                        "later duplicate write in same transaction wins", payload(image, src)));
                events.add(RuleEvent.of("dup", "jbd2.replay.duplicate-last-write-wins", "info",
                        "Duplicate write of fs block " + fs + " in seq "
                                + (seq % SequenceRing.MOD) + "; earlier write shadowed",
                        actionId, List.of("act:" + earlier.actionId()), seq));
                continue;
            }

            Long revokeSeq = revokeAt.get(fs);
            if (revokeSeq != null && revoked(revokeSeq, seq)) {
                actions.add(new ReplayAction(actionId, seq, fs, src,
                        ActionDecision.SUPPRESSED_BY_REVOKE,
                        "write seq " + (seq % SequenceRing.MOD)
                                + " is within revoke range <= " + (revokeSeq % SequenceRing.MOD),
                        null));
                events.add(RuleEvent.of("revoke-hit", RULE_REVOKE, "info",
                        "Revoke hit: fs block " + fs + " write seq " + (seq % SequenceRing.MOD)
                                + " suppressed by revoke range <= " + (revokeSeq % SequenceRing.MOD),
                        actionId, List.of("revoke:" + fs + ":" + (revokeSeq % SequenceRing.MOD)),
                        seq));
                continue;
            }

            actions.add(new ReplayAction(actionId, seq, fs, src, ActionDecision.APPLY,
                    "complete committed write in wrap-aware sequence order", payload(image, src)));
        }
    }

    private boolean revoked(long revokeSeq, long writeSeq) {
        long d = SequenceRing.forwardDistance(writeSeq, revokeSeq);
        return d == 0 || d < SequenceRing.MOD / 2;
    }

    private boolean atOrAfterCrash(long physical, long crashPoint, JournalImage image) {
        long ringStart = image.ringStart();
        long max = image.maxJournalBlocks().value();
        long dPhys = (physical - ringStart + max) % max;
        long dCrash = (crashPoint - ringStart + max) % max;
        return dPhys >= dCrash;
    }

    private ReplayAction lastActionFor(List<ReplayAction> actions, long fs) {
        ReplayAction found = null;
        for (ReplayAction a : actions) {
            if (a.fsBlock() == fs) {
                found = a;
            }
        }
        return found;
    }

    private ReplayAction shadow(ReplayAction a) {
        return new ReplayAction(a.actionId(), a.sequence(), a.fsBlock(), a.sourcePhysical(),
                ActionDecision.DUP_SHADOWED, "shadowed by a later duplicate write in same transaction",
                null);
    }

    private byte[] payload(JournalImage image, long physical) {
        RawBlock rb = image.blockAt(physical);
        return rb == null ? null : rb.bytes().clone();
    }
}
