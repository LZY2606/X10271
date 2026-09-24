package jbd2lab.core;

import jbd2lab.model.Domain.*;

import java.util.*;

public final class ReplayEngine {
    private static final String R_COMPLETE_COMMIT = "R-JBD2-COMPLETE-COMMIT";
    private static final String R_REVOKE_SCOPE = "R-JBD2-REVOKE-SEQUENCE-SCOPE";
    private static final String R_CIRCULAR_SEQ = "R-JBD2-CIRCULAR-SEQUENCE";
    private static final String R_CHECKSUM_FRONTIER = "R-JBD2-CHECKSUM-BOUNDARY-UNCERTAIN";
    private static final String R_BLOCK_SIZE = "R-EXT4-VALID-BLOCK-SIZE";
    private static final String R_IDEMPOTENCE = "R-REPLAY-IDEMPOTENT";

    public DerivedGraph derive(Snapshot snapshot) {
        List<JournalBlock> observed = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(snapshot.superblock().diagnostics());
        validateBlockSize(snapshot.superblock().blockSize(), diagnostics);

        List<RawBlock> orderedRaw = snapshot.blocks().stream()
                .sorted(Comparator.comparingInt(RawBlock::ringSlot))
                .toList();
        for (RawBlock raw : orderedRaw) {
            if (raw.ringSlot() > snapshot.crashPoint().observedThroughRingSlot()) {
                continue;
            }
            JournalBlock block = Jbd2Codec.decode(raw);
            observed.add(block);
            block.diagnostics().stream().filter(d -> !diagnostics.contains(d)).forEach(diagnostics::add);
        }
        snapshot.probes().stream()
                .filter(probe -> "invalid-block-size".equals(probe.role()))
                .forEach(probe -> diagnostics.add("EXT4_INVALID_BLOCK_SIZE_PROBE:raw=" + probe.id()
                        + ";source=" + probe.sourceUri()));

        List<TransactionView> transactions = buildTransactions(observed);
        Set<Long> replayableSequences = new TreeSet<>();
        boolean checksumFailureVisible = observed.stream().anyMatch(block -> !block.checksumValid());
        for (TransactionView txn : transactions) {
            if (txn.replayable()) {
                replayableSequences.add(txn.sequence());
            }
        }

        List<RevokeHit> revokeHits = new ArrayList<>();
        List<CoverageStep> coverage = buildCoverage(orderedRaw, observed, transactions, replayableSequences, revokeHits);
        List<ReplayOperation> operations = buildReplay(coverage);
        List<DerivedBlock> derivedBlocks = buildDerivedBlocks(coverage, operations);
        List<FrontierCandidate> candidates = buildCandidates(transactions, checksumFailureVisible);
        String selected = candidates.stream().filter(FrontierCandidate::selectedByDefault).findFirst()
                .map(FrontierCandidate::id).orElse(null);

        return new DerivedGraph(
                List.copyOf(observed),
                List.copyOf(transactions),
                List.copyOf(coverage),
                List.copyOf(revokeHits),
                List.copyOf(operations),
                List.copyOf(derivedBlocks),
                List.copyOf(candidates),
                selected,
                List.copyOf(diagnostics)
        );
    }

    private void validateBlockSize(Quantity quantity, List<String> diagnostics) {
        if (quantity.value() == null) {
            diagnostics.add("EXT4_BLOCK_SIZE_UNCONVERTED:raw=" + quantity.raw()
                    + ";unit=" + quantity.unit() + ";diagnostic=" + quantity.diagnostic());
            return;
        }
        long value = quantity.value();
        if (value < 1024 || value > 65536 || Long.bitCount(value) != 1) {
            diagnostics.add("EXT4_INVALID_BLOCK_SIZE:value=" + value + ";basis=" + quantity.coordinateBasis());
        }
    }

    private List<TransactionView> buildTransactions(List<JournalBlock> observed) {
        List<TransactionView> groups = new ArrayList<>();
        TransactionBuilder current = null;
        for (JournalBlock block : observed) {
            boolean startsGroup = block.type() == Domain.DESCRIPTOR || block.type() == Domain.REVOKE;
            if (startsGroup) {
                if (current != null) {
                    groups.add(current.finish(false));
                }
                current = new TransactionBuilder(block);
            } else if (current != null) {
                current.add(block);
            } else if (block.type() != Domain.COMMIT) {
                groups.add(orphan(block));
            }
            if (block.type() == Domain.COMMIT && current != null) {
                groups.add(current.finish(true));
                current = null;
            }
        }
        if (current != null) {
            groups.add(current.finish(false));
        }
        return groups;
    }

    private TransactionView orphan(JournalBlock block) {
        return new TransactionView(
                "txn-orphan-slot-" + block.ringSlot(),
                block.sequence(),
                "ORPHAN",
                false,
                block.checksumValid(),
                false,
                block.ringSlot(),
                block.ringSlot(),
                List.of(block.rawBlockId()),
                List.of("JBD2_ORPHAN_BLOCK:raw=" + block.rawBlockId()),
                List.of(R_COMPLETE_COMMIT)
        );
    }

    private static final class TransactionBuilder {
        private final JournalBlock first;
        private final List<JournalBlock> blocks = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();
        private boolean committed;

        private TransactionBuilder(JournalBlock first) {
            this.first = first;
            add(first);
        }

        private void add(JournalBlock block) {
            blocks.add(block);
            if (block.sequence() != first.sequence()) {
                diagnostics.add("JBD2_SEQUENCE_MISMATCH_IN_GROUP:raw=" + block.rawBlockId()
                        + ";expected=" + Long.toUnsignedString(first.sequence())
                        + ";actual=" + Long.toUnsignedString(block.sequence()));
            }
            if (!block.checksumValid() || !block.magicValid()) {
                diagnostics.addAll(block.diagnostics());
            }
            if (block.type() == Domain.COMMIT) {
                committed = true;
            }
        }

        private TransactionView finish(boolean commitBlockSeen) {
            boolean descriptorValid = first.type() != Domain.DESCRIPTOR || !first.descriptorTags().isEmpty();
            long expectedDataBlocks = first.type() == Domain.DESCRIPTOR ? first.descriptorTags().size() : 0;
            long actualDataBlocks = blocks.stream().filter(block -> block.type() == Domain.DATA).count();
            if (expectedDataBlocks != actualDataBlocks) {
                diagnostics.add("JBD2_DESCRIPTOR_DATA_COUNT_MISMATCH:expected=" + expectedDataBlocks
                        + ";actual=" + actualDataBlocks);
            }
            if (!descriptorValid) {
                diagnostics.add("JBD2_DESCRIPTOR_HAS_NO_TAGS:raw=" + first.rawBlockId());
            }
            boolean allChecksumsOk = blocks.stream().allMatch(block -> block.checksumValid() && block.magicValid());
            boolean complete = commitBlockSeen && committed && descriptorValid
                    && expectedDataBlocks == actualDataBlocks
                    && blocks.stream().allMatch(block -> block.sequence() == first.sequence());
            boolean replayable = complete && allChecksumsOk;
            String state = !commitBlockSeen ? "HALF_TRANSACTION"
                    : !allChecksumsOk ? "CHECKSUM_FAILED"
                    : !complete ? "MALFORMED_COMMITTED_TRANSACTION"
                    : "COMPLETE";
            List<String> rules = new ArrayList<>(List.of(R_COMPLETE_COMMIT));
            if (blocks.stream().anyMatch(block -> block.type() == Domain.REVOKE)) {
                rules.add(R_REVOKE_SCOPE);
            }
            return new TransactionView(
                    "txn-seq-" + Long.toUnsignedString(first.sequence()),
                    first.sequence(),
                    state,
                    complete,
                    allChecksumsOk,
                    replayable,
                    first.ringSlot(),
                    blocks.get(blocks.size() - 1).ringSlot(),
                    blocks.stream().map(JournalBlock::rawBlockId).toList(),
                    List.copyOf(diagnostics),
                    List.copyOf(rules)
            );
        }
    }

    private List<CoverageStep> buildCoverage(
            List<RawBlock> orderedRaw,
            List<JournalBlock> observed,
            List<TransactionView> transactions,
            Set<Long> replayableSequences,
            List<RevokeHit> revokeHits
    ) {
        Map<String, RawBlock> rawById = orderedRaw.stream().collect(
                HashMap::new,
                (map, raw) -> map.put(raw.id(), raw),
                HashMap::putAll
        );
        Map<Long, TransactionView> bySequence = transactions.stream().collect(
                HashMap::new,
                (map, txn) -> map.put(txn.sequence(), txn),
                HashMap::putAll
        );
        Map<String, JournalBlock> observedById = observed.stream().collect(
                HashMap::new,
                (map, block) -> map.put(block.rawBlockId(), block),
                HashMap::putAll
        );
        List<CoverageStep> writes = new ArrayList<>();
        List<TransactionView> replayableInPhysicalOrder = transactions.stream()
                .filter(TransactionView::replayable)
                .sorted(Comparator.comparingInt(TransactionView::firstSlot))
                .toList();
        Map<Long, RevokeMarker> latestRevoke = new HashMap<>();
        Map<String, RevokeMarker> writeRevokedBy = new HashMap<>();

        for (TransactionView txn : replayableInPhysicalOrder) {
            JournalBlock header = observedById.get(txn.blockIds().get(0));
            if (header.type() == Domain.REVOKE) {
                for (long target : header.revokedBlocks()) {
                    RevokeMarker marker = new RevokeMarker(txn, header, target);
                    for (CoverageStep step : writes) {
                        if (step.filesystemBlock() == target && !step.revoked()
                                && circularBefore(step.sequence(), txn.sequence())) {
                            writeRevokedBy.put(step.id(), marker);
                        }
                    }
                    latestRevoke.put(target, marker);
                }
                continue;
            }
            if (header.type() != Domain.DESCRIPTOR) {
                continue;
            }
            int dataIndex = 0;
            Map<Long, Integer> seenTargets = new HashMap<>();
            for (String blockId : txn.blockIds()) {
                JournalBlock block = observedById.get(blockId);
                if (block.type() != Domain.DATA) {
                    continue;
                }
                DescriptorTag tag = header.descriptorTags().get(dataIndex++);
                long target = tag.targetFilesystemBlock();
                int occurrence = seenTargets.merge(target, 1, Integer::sum);
                String stepId = "coverage-seq-" + Long.toUnsignedString(txn.sequence())
                        + "-fsblk-" + target + "-occurrence-" + occurrence;
                CoverageStep priorForTarget = writes.stream()
                        .filter(step -> step.filesystemBlock() == target && step.sequence() == txn.sequence())
                        .reduce((first, second) -> second).orElse(null);
                boolean superseded = priorForTarget != null;
                CoverageStep step = new CoverageStep(
                        stepId,
                        writes.size() + 1,
                        txn.sequence(),
                        target,
                        blockId,
                        txn.id(),
                        superseded,
                        false
                );
                writes.add(step);
                RevokeMarker marker = latestRevoke.get(target);
                if (marker != null && circularBefore(marker.txn.sequence(), txn.sequence())) {
                    writeRevokedBy.put(stepId, marker);
                }
            }
        }

        List<CoverageStep> result = new ArrayList<>();
        for (CoverageStep step : writes) {
            RevokeMarker marker = writeRevokedBy.get(step.id());
            boolean revoked = marker != null;
            if (revoked) {
                revokeHits.add(new RevokeHit(
                        "revoke-hit-" + Long.toUnsignedString(marker.txn.sequence())
                                + "-over-seq-" + Long.toUnsignedString(step.sequence())
                                + "-fsblk-" + step.filesystemBlock(),
                        marker.txn.sequence(),
                        step.sequence(),
                        step.filesystemBlock(),
                        step.dataBlockId(),
                        marker.block.rawBlockId(),
                        R_REVOKE_SCOPE
                ));
            }
            result.add(new CoverageStep(
                    step.id(),
                    step.order(),
                    step.sequence(),
                    step.filesystemBlock(),
                    step.dataBlockId(),
                    step.transactionId(),
                    step.supersededWithinTransaction(),
                    revoked
            ));
        }
        return result;
    }

    private record RevokeMarker(TransactionView txn, JournalBlock block, long target) {
    }

    public static boolean circularBefore(long left, long right) {
        return Integer.compareUnsigned((int) left, (int) right) < 0;
    }

    private List<ReplayOperation> buildReplay(List<CoverageStep> coverage) {
        List<ReplayOperation> result = new ArrayList<>();
        Map<Long, CoverageStep> effective = new HashMap<>();
        for (CoverageStep step : coverage) {
            if (!step.revoked()) {
                effective.put(step.filesystemBlock(), step);
            }
        }
        for (CoverageStep step : coverage) {
            if (step.revoked() || !effective.get(step.filesystemBlock()).id().equals(step.id())) {
                continue;
            }
            String digest = Digest.sha256Hex("journal-data:" + step.dataBlockId());
            String first = Digest.sha256Hex("apply-once:" + step.dataBlockId() + ":" + step.filesystemBlock());
            String second = Digest.sha256Hex("apply-twice:" + step.dataBlockId() + ":" + step.filesystemBlock());
            result.add(new ReplayOperation(
                    "replay-seq-" + Long.toUnsignedString(step.sequence())
                            + "-fsblk-" + step.filesystemBlock(),
                    result.size() + 1,
                    step.sequence(),
                    step.filesystemBlock(),
                    step.dataBlockId(),
                    digest,
                    first,
                    second,
                    false,
                    List.of(step.id(), step.transactionId(), R_COMPLETE_COMMIT, R_REVOKE_SCOPE, R_IDEMPOTENCE)
            ));
        }
        return result;
    }

    private List<DerivedBlock> buildDerivedBlocks(List<CoverageStep> coverage, List<ReplayOperation> operations) {
        Map<Long, List<CoverageStep>> byTarget = new TreeMap<>();
        for (CoverageStep step : coverage) {
            byTarget.computeIfAbsent(step.filesystemBlock(), ignored -> new ArrayList<>()).add(step);
        }
        List<DerivedBlock> result = new ArrayList<>();
        for (Map.Entry<Long, List<CoverageStep>> entry : byTarget.entrySet()) {
            long target = entry.getKey();
            CoverageStep winner = entry.getValue().stream()
                    .filter(step -> !step.revoked())
                    .reduce((first, second) -> second)
                    .orElse(null);
            String state = winner == null ? "REVOKED_NO_LATER_REWRITE" : "DERIVED";
            ReplayOperation operation = operations.stream()
                    .filter(op -> op.filesystemBlock() == target)
                    .findFirst().orElse(null);
            List<String> chain = new ArrayList<>();
            entry.getValue().forEach(step -> chain.add(step.id()));
            if (winner != null) {
                chain.add(winner.dataBlockId());
                chain.add(winner.transactionId());
            }
            result.add(new DerivedBlock(
                    "derived-fsblk-" + target,
                    target,
                    state,
                    winner == null ? null : winner.dataBlockId(),
                    winner == null ? null : winner.sequence(),
                    entry.getValue().stream().map(CoverageStep::dataBlockId).toList(),
                    List.copyOf(chain),
                    operation == null ? Digest.sha256Hex("revoked:" + target) : operation.sourceDigestHex()
            ));
        }
        return result;
    }

    private List<FrontierCandidate> buildCandidates(List<TransactionView> transactions, boolean checksumFailureVisible) {
        List<String> completeIds = transactions.stream()
                .filter(TransactionView::replayable)
                .sorted(Comparator.comparingInt(TransactionView::firstSlot))
                .map(TransactionView::id)
                .toList();
        int firstBadIndex = -1;
        for (int index = 0; index < transactions.size(); index++) {
            if (!transactions.get(index).replayable()
                    && (transactions.get(index).state().equals("CHECKSUM_FAILED")
                    || transactions.get(index).diagnostics().stream().anyMatch(d -> d.contains("BAD_CHECKSUM")))) {
                firstBadIndex = index;
                break;
            }
        }
        if (!checksumFailureVisible || firstBadIndex < 0) {
            return List.of(new FrontierCandidate(
                    "frontier-only-valid-commit",
                    "All complete valid transactions",
                    true,
                    false,
                    "No checksum breakpoint is visible; the complete-commit frontier is unique.",
                    completeIds,
                    List.of(R_COMPLETE_COMMIT, R_CIRCULAR_SEQ)
            ));
        }
        TransactionView bad = transactions.get(firstBadIndex);
        List<String> conservative = transactions.stream()
                .filter(TransactionView::replayable)
                .filter(txn -> txn.firstSlot() < bad.firstSlot())
                .sorted(Comparator.comparingInt(TransactionView::firstSlot))
                .map(TransactionView::id)
                .toList();
        List<String> after = transactions.stream()
                .filter(TransactionView::replayable)
                .filter(txn -> txn.firstSlot() > bad.lastSlot())
                .sorted(Comparator.comparingInt(TransactionView::firstSlot))
                .map(TransactionView::id)
                .toList();
        List<String> continuing = new ArrayList<>(conservative);
        continuing.addAll(after);
        return List.of(
                new FrontierCandidate(
                        "frontier-conservative-before-checksum",
                        "Stop before first checksum breakpoint",
                        true,
                        false,
                        "A bad checksum makes the local boundary unsafe, so this candidate retains no state at or after it.",
                        List.copyOf(conservative),
                        List.of(R_COMPLETE_COMMIT, R_CHECKSUM_FRONTIER)
                ),
                new FrontierCandidate(
                        "frontier-independent-after-checksum",
                        "Include independently complete later transactions",
                        false,
                        true,
                        "Later commits may be independent valid material, but crossing a corrupt boundary remains an explicit uncertain decision.",
                        List.copyOf(continuing),
                        List.of(R_COMPLETE_COMMIT, R_CHECKSUM_FRONTIER)
                )
        );
    }
}
