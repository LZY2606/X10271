package lab.jbd2.core;

import lab.jbd2.binary.Jbd2Format;
import lab.jbd2.model.BlockKind;
import lab.jbd2.model.JournalImage;
import lab.jbd2.model.JournalTxn;
import lab.jbd2.model.Quantity;
import lab.jbd2.model.RawBlock;
import lab.jbd2.model.RuleEvent;
import lab.jbd2.model.ScanResult;
import lab.jbd2.model.Tag;
import lab.jbd2.model.TxnStatus;

import java.util.ArrayList;
import java.util.List;

import static lab.jbd2.binary.Jbd2Format.HEADER_LEN;

/**
 * Scans the durable prefix of a journal ring and reconstructs descriptor / data /
 * commit / revoke groups. The scanner never fabricates certainty: a missing commit
 * yields an incomplete (un-replayable) transaction, and a checksum mismatch leaves the
 * replay boundary explicitly uncertain with both legal boundaries retained.
 */
public final class JournalScanner {

    private static final String RULE_INVALID_BS = "ext4.journal.blocksize.must-be-power-of-two";
    private static final String RULE_MAGIC = "ext4.journal.magic";
    private static final String RULE_COMPLETE = "jbd2.txn.replayable-only-if-complete-commit";
    private static final String RULE_CSUMS = "jbd2.checksum.boundary-uncertain-after-failure";

    public ScanResult scan(JournalImage image) {
        List<String> fatal = new ArrayList<>();
        List<RuleEvent> events = new ArrayList<>();

        if (!image.blockSize().convertible()) {
            String d = image.blockSize().diagnostic();
            fatal.add("invalid block size retained at origin: " + d);
            events.add(RuleEvent.of("diagnostic", RULE_INVALID_BS, "fatal",
                    "Block size cannot be converted: " + d, "param:blockSize",
                    List.of("src:blockSize"), 0));
            return new ScanResult(0, 0, false, List.of(), events, fatal);
        }
        if (!image.validBlockSize()) {
            fatal.add("invalid block size " + image.blockSize().raw()
                    + ": must be a power of two in [1024, 65536] B");
            events.add(RuleEvent.of("diagnostic", RULE_INVALID_BS, "fatal",
                    "Block size " + image.blockSize().raw() + " is not a legal ext4 block size",
                    "param:blockSize", List.of("src:blockSize"), 0));
            return new ScanResult(0, 0, false, List.of(), events, fatal);
        }
        int checksumType = image.checksumVersion() == null ? 0 : image.checksumVersion().code;

        long max = image.maxJournalBlocks().value();
        RawBlock sb = image.blockAt(0);
        if (sb == null || !Jbd2Format.magicOk(sb.bytes())
                || Jbd2Format.kindOf(sb.bytes()) != BlockKind.SUPERBLOCK) {
            fatal.add("journal superblock missing or bad magic at physical block 0");
            events.add(RuleEvent.of("diagnostic", RULE_MAGIC, "fatal",
                    "Journal superblock magic/header invalid", "block:0",
                    List.of("src:block:0"), 0));
            return new ScanResult(0, 0, false, List.of(), events, fatal);
        }

        long ringStart = image.ringStart();
        long crashTail = image.crashTailBlock();
        long durable = ((crashTail - ringStart) % max + max) % max;

        List<JournalTxn> txns = new ArrayList<>();
        boolean[] wrapped = {false};

        long expectedUnwrapped = image.expectedFirstSequence().get(0).value();
        long order = 0;
        boolean ringWrappedScan = ringStart + durable > max;

        long consumed = 0;
        while (consumed < durable) {
            long physical = (ringStart + consumed) % max;
            RawBlock rb = image.blockAt(physical);
            if (rb == null || !Jbd2Format.magicOk(rb.bytes())) {
                events.add(RuleEvent.of("gap", "jbd2.ring.durable-prefix", "warn",
                        "Unreadable/non-magic block inside durable prefix; stopping scan",
                        "block:" + physical,
                        List.of("src:block:" + physical), order++));
                break;
            }
            BlockKind kind = Jbd2Format.kindOf(rb.bytes());
            long rawSeq = Jbd2Format.sequenceOf(rb.bytes());

            if (kind == BlockKind.DESCRIPTOR) {
                long unwrapped = SequenceRing.unwrapNext(expectedUnwrapped - 1, rawSeq);
                expectedUnwrapped = unwrapped + 1;
                if (unwrapped >= SequenceRing.MOD) {
                    wrapped[0] = true;
                }
                JournalTxn txn = parseTransaction(image, physical, rawSeq, unwrapped,
                        wrapped[0], order++, checksumType);
                txns.add(txn);
                consumed += txn.groupLength();
            } else {
                if (kind == BlockKind.DATA || kind == BlockKind.REVOKE || kind == BlockKind.COMMIT) {
                    events.add(RuleEvent.of("stray", "jbd2.ring.grouping", "info",
                            kind + " block outside a descriptor group",
                            "block:" + physical, List.of("src:block:" + physical), order++));
                }
                consumed++;
            }
        }

        for (JournalTxn t : txns) {
            events.add(txnEvent(t));
        }
        return new ScanResult(ringStart, durable, ringWrappedScan, List.copyOf(txns),
                List.copyOf(events), List.of());
    }

    private RuleEvent txnEvent(JournalTxn t) {
        String subject = "txn:" + t.sequence();
        List<String> inputs = new ArrayList<>();
        inputs.add("block:" + t.descriptorPhysical());
        if (t.commitPhysical() >= 0) {
            inputs.add("block:" + t.commitPhysical());
        }
        return switch (t.status()) {
            case COMPLETE -> RuleEvent.of("commit", RULE_COMPLETE, "ok",
                    "Transaction " + t.sequence() + " has descriptor + valid commit; replayable",
                    subject, inputs, t.orderIndex());
            case INCOMPLETE -> RuleEvent.of("half", RULE_COMPLETE, "warn",
                    "Transaction " + t.sequence() + " has no commit (half transaction); not replayable",
                    subject, inputs, t.orderIndex());
            case CORRUPT -> RuleEvent.of("checksum", RULE_CSUMS, "uncertain",
                    "Transaction " + t.sequence()
                            + " checksum failed; replay boundary after it is uncertain",
                    subject, inputs, t.orderIndex());
        };
    }

    private long consumeUntilEndOrNext(JournalImage image, long descPhysical, long max, long crashTail) {
        long step = 1;
        while (true) {
            long p = (descPhysical + step) % max;
            if (p == crashTail || step > max) {
                return step;
            }
            RawBlock rb = image.blockAt(p);
            if (rb != null && Jbd2Format.magicOk(rb.bytes())) {
                BlockKind k = Jbd2Format.kindOf(rb.bytes());
                if (k == BlockKind.DESCRIPTOR) {
                    return step;
                }
            }
            step++;
        }
    }

    private JournalTxn parseTransaction(JournalImage image, long descPhysical, long rawSeq,
                                        long unwrapped, boolean wrapped, long order,
                                        int checksumType) {
        long max = image.maxJournalBlocks().value();
        RawBlock desc = image.blockAt(descPhysical);
        int stride = Jbd2Format.tagStride(checksumType);

        List<Tag> tags = new ArrayList<>();
        boolean tagChecksumOk = true;
        int off = HEADER_LEN;
        int tagBytes = 0;
        while (off + stride <= desc.bytes().length - 4) {
            Jbd2Format.TagRead tr = Jbd2Format.readTag(desc.bytes(), off, checksumType);
            if (tr.fsBlock() == 0 && tr.flags() == 0 && tagBytes > 0) {
                break;
            }
            tagChecksumOk &= tr.checksumOk();
            long dataPhysical = (descPhysical + 1 + tags.size()) % max;
            tags.add(new Tag(tr.fsBlock(), dataPhysical, tr.last(),
                    (tr.flags() & Jbd2Format.FLAG_ESCAPE) != 0));
            tagBytes += stride;
            off = tr.nextOff();
            if (tr.last()) {
                break;
            }
        }
        boolean descCsum = tagChecksumOk
                && Jbd2Format.descriptorChecksumOk(desc.bytes(), tagBytes, checksumType);

        long commitPhysical = -1;
        boolean commitCsum = false;
        List<Long> revokes = new ArrayList<>();

        long groupLen = tags.size() == 0 ? 0 : tags.size();
        long scanLen = Math.max(groupLen, 1);
        for (long step = scanLen; step < max; step++) {
            long p = (descPhysical + step) % max;
            RawBlock rb = image.blockAt(p);
            if (rb == null || !Jbd2Format.magicOk(rb.bytes())) {
                break;
            }
            BlockKind k = Jbd2Format.kindOf(rb.bytes());
            if (k == BlockKind.DESCRIPTOR) {
                break;
            }
            if (k == BlockKind.REVOKE) {
                long count = Jbd2Format.getU32(rb.bytes(), Jbd2Format.REVOKE_OFF_COUNT);
                for (int i = 0; i < count; i++) {
                    revokes.add(Jbd2Format.getU32(rb.bytes(),
                            Jbd2Format.REVOKE_OFF_TABLE + i * 4));
                }
                if (!Jbd2Format.revokeChecksumOk(rb.bytes(), checksumType)) {
                    descCsum = false;
                }
            }
            if (k == BlockKind.COMMIT) {
                commitPhysical = p;
                commitCsum = Jbd2Format.commitChecksumOk(rb.bytes(), checksumType);
                break;
            }
        }

        TxnStatus status;
        String diagnosis;
        if (commitPhysical < 0) {
            status = TxnStatus.INCOMPLETE;
            diagnosis = "descriptor without commit inside durable prefix (half transaction)";
        } else if (!descCsum || !commitCsum) {
            status = TxnStatus.CORRUPT;
            diagnosis = !descCsum
                    ? "descriptor/revoke/tag checksum mismatch"
                    : "commit checksum mismatch";
        } else {
            status = TxnStatus.COMPLETE;
            diagnosis = "complete committed transaction";
        }
        return new JournalTxn(descPhysical, commitPhysical, unwrapped, wrapped, order,
                status, descCsum, commitCsum, List.copyOf(tags), List.copyOf(revokes), diagnosis);
    }
}
