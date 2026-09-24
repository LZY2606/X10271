package lab.ext4jbd2.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the JBD2 journal ring from the superblock start coordinate.
 *
 * Faithful semantics implemented here:
 *  - ring blocks are visited in ring order, wrapping from s_maxlen-1 to s_first
 *  - sequence numbers are compared modulo 2^32 (see SeqNum)
 *  - only descriptor+data[+revoke]+commit with matching sequence and valid
 *    checksums is a committed transaction
 *  - a missing/torn commit is a half transaction (never replayed)
 *  - a checksum failure leaves the boundary uncertain: the mainline scan stops
 *    and both legal continuations are retained as candidates
 */
public final class JournalScanner {
    private JournalScanner() {}

    public static ScanResult scan(byte[][] journal, long first, long maxlen,
                                  long firstSequence, long start, int checksumType,
                                  Integer horizon, RuleBook rules,
                                  Quantity blockSizeQuantity, String idPrefix) {
        ScanResult res = new ScanResult();
        Quantity bsq = blockSizeQuantity == null ? null : blockSizeQuantity.normalize();
        if (bsq == null || bsq.normalized == null) {
            abortInvalidBlockSize(res, start,
                    bsq == null ? "missing block size" : bsq.diagnostic);
            return res;
        }
        if (!rules.blockSizeAllowlist.contains(bsq.normalized)) {
            abortInvalidBlockSize(res, start,
                    "block size " + bsq.normalized + " B is not in the allowlist "
                            + rules.blockSizeAllowlist);
            return res;
        }
        if (first <= 0 || maxlen <= first) {
            res.aborted = true;
            res.abortReason = "invalid journal geometry first=" + first + " maxlen=" + maxlen;
            return res;
        }
        if (start == 0) {
            res.endOfLog = (int) first;
            res.observations.add(new ScanResult.Observation("EMPTY_LOG", (int) first,
                    "s_start == 0: no transactions in the log"));
            return res;
        }
        int ringSize = (int) (maxlen - first);
        int pos = (int) start;
        long expectedSeq = firstSequence & 0xFFFFFFFFL;

        for (int steps = 0; steps < ringSize; steps++) {
            if (horizon != null && pos == horizon) {
                res.observations.add(new ScanResult.Observation("CRASH_HORIZON", pos,
                        "expected crash point: blocks from journal#" + pos + " were never persisted"));
                res.endOfLog = pos;
                return res;
            }
            JournalBlock blk = readBlock(journal, pos, checksumType);
            if (blk == null) {
                res.observations.add(new ScanResult.Observation("END_OF_LOG", pos,
                        "unformatted / absent block"));
                res.endOfLog = pos;
                return res;
            }
            res.ringWalk.add(pos);

            if (blk.type == Jbd2.TYPE_DESCRIPTOR) {
                if (SeqNum.compare(blk.sequence, expectedSeq) != 0) {
                    res.observations.add(new ScanResult.Observation("END_OF_LOG", pos,
                            "descriptor sequence " + Hex.u32(blk.sequence)
                                    + " does not match expected " + Hex.u32(expectedSeq)));
                    res.endOfLog = pos;
                    return res;
                }
                if (blk.checksumOk != null && !blk.checksumOk) {
                    checksumBreak(res, journal, pos, first, ringSize, checksumType,
                            horizon, expectedSeq, "descriptor checksum mismatch",
                            idPrefix, null);
                    return res;
                }
                ScanResult.Txn txn = new ScanResult.Txn(blk.sequence, pos);
                blk.tags.forEach(t -> txn.fsBlocks.add(t.fsBlock));

                int p = pos;
                for (int i = 0; i < blk.tags.size(); i++) {
                    p = advance(p, first, ringSize, res);
                    if (horizon != null && p == horizon) {
                        halfTransaction(res, txn, p, true, expectedSeq, idPrefix);
                        return res;
                    }
                    txn.dataCoords.add(p);
                }
                int afterData = p;
                JournalBlock next = readBlock(journal, afterData, checksumType);
                if (next != null && next.type == Jbd2.TYPE_REVOKE
                        && SeqNum.compare(next.sequence, expectedSeq) == 0) {
                    if (next.checksumOk != null && !next.checksumOk) {
                        checksumBreak(res, journal, pos, first, ringSize, checksumType,
                                horizon, expectedSeq, "revoke checksum mismatch",
                                idPrefix, txn);
                        return res;
                    }
                    res.ringWalk.add(afterData);
                    txn.revokeCoord = afterData;
                    txn.revokeBlocks = new ArrayList<>(next.revokeBlocks);
                    int rp = advance(afterData, first, ringSize, res);
                    if (horizon != null && rp == horizon) {
                        halfTransaction(res, txn, rp, true, expectedSeq, idPrefix);
                        return res;
                    }
                    JournalBlock commit = readBlock(journal, rp, checksumType);
                    if (!completeCommit(res, commit, rp, expectedSeq, txn, journal, first,
                            ringSize, checksumType, horizon, idPrefix)) {
                        return res;
                    }
                    expectedSeq = SeqNum.next(expectedSeq);
                    pos = advance(rp, first, ringSize, res);
                } else if (next != null && next.type == Jbd2.TYPE_COMMIT
                        && SeqNum.compare(next.sequence, expectedSeq) == 0) {
                    if (!completeCommit(res, next, afterData, expectedSeq, txn, journal, first,
                            ringSize, checksumType, horizon, idPrefix)) {
                        return res;
                    }
                    expectedSeq = SeqNum.next(expectedSeq);
                    pos = advance(afterData, first, ringSize, res);
                } else {
                    halfTransaction(res, txn, afterData, false, expectedSeq, idPrefix);
                    return res;
                }
            } else if (blk.type == Jbd2.TYPE_COMMIT || blk.type == Jbd2.TYPE_REVOKE) {
                ScanResult.Anomaly orphan = new ScanResult.Anomaly(
                        idPrefix + "/orphan-" + Jbd2.typeName(blk.type) + "-" + pos,
                        "ORPHAN_" + Jbd2.typeName(blk.type).toUpperCase(), pos, blk.sequence,
                        Jbd2.typeName(blk.type) + " block without a preceding descriptor");
                res.anomalies.add(orphan);
                res.observations.add(new ScanResult.Observation("END_OF_LOG", pos,
                        "orphan " + Jbd2.typeName(blk.type)));
                res.endOfLog = pos;
                return res;
            } else {
                res.observations.add(new ScanResult.Observation("END_OF_LOG", pos,
                        blk.parseError != null ? blk.parseError : "unexpected block"));
                res.endOfLog = pos;
                return res;
            }

            if (SeqNum.compare(expectedSeq, firstSequence) == 0) {
                res.endOfLog = pos;
                return res;
            }
        }
        res.endOfLog = pos;
        return res;
    }

    private static void abortInvalidBlockSize(ScanResult res, long start, String why) {
        ScanResult.Anomaly a = new ScanResult.Anomaly(
                "anom/invalid-block-size", "INVALID_BLOCK_SIZE", (int) start, null,
                "invalid block size: " + why + "; value kept in place with this diagnostic");
        res.anomalies.add(a);
        res.aborted = true;
        res.abortReason = a.message;
        res.endOfLog = (int) start;
    }

    private static int advance(int pos, long first, int ringSize, ScanResult res) {
        long next = first + ((pos - first + 1) % ringSize);
        if (next < pos) {
            res.observations.add(new ScanResult.Observation("RING_WRAP", (int) next,
                    "journal ring wrapped journal#" + pos + " -> journal#" + next));
        }
        return (int) next;
    }

    private static JournalBlock readBlock(byte[][] journal, int pos, int checksumType) {
        if (pos < 0 || pos >= journal.length || journal[pos] == null) return null;
        JournalBlock blk = JournalCodec.parse(journal[pos], pos, checksumType);
        if (!blk.hasMagic()) return null;
        return blk;
    }

    private static void halfTransaction(ScanResult res, ScanResult.Txn txn, int at,
                                        boolean horizonHit, long expectedSeq, String prefix) {
        txn.status = "INCOMPLETE";
        res.txns.add(txn);
        String msg = horizonHit
                ? "transaction " + Hex.u32(expectedSeq) + " cut by the crash horizon at journal#" + at
                  + ": descriptor + " + txn.dataCoords.size() + " data block(s) survived, commit did not"
                : "transaction " + Hex.u32(expectedSeq) + " has no matching commit at journal#" + at
                  + ": partial descriptor/data are never replayed";
        ScanResult.Anomaly a = new ScanResult.Anomaly(
                prefix + "/halftxn-" + Hex.u32(expectedSeq), "HALF_TRANSACTION",
                txn.descCoord, expectedSeq, msg);
        res.anomalies.add(a);
        res.observations.add(new ScanResult.Observation(horizonHit ? "CRASH_HORIZON" : "END_OF_LOG",
                at, msg));
        res.endOfLog = at;
    }

    private static boolean completeCommit(ScanResult res, JournalBlock commit, int coord,
                                          long expectedSeq, ScanResult.Txn txn,
                                          byte[][] journal, long first,
                                          int ringSize, int checksumType, Integer horizon,
                                          String prefix) {
        if (commit == null || commit.type != Jbd2.TYPE_COMMIT
                || SeqNum.compare(commit.sequence, expectedSeq) != 0) {
            halfTransaction(res, txn, coord, false, expectedSeq, prefix);
            return false;
        }
        if (commit.checksumOk != null && !commit.checksumOk) {
            checksumBreak(res, journal, txn.descCoord, first, ringSize, checksumType, horizon,
                    expectedSeq, "commit checksum mismatch at journal#" + coord, prefix, txn);
            return false;
        }
        res.ringWalk.add(coord);
        txn.commitCoord = coord;
        txn.status = "COMMITTED";
        if (SeqNum.compare(expectedSeq, 0xFFFFFFFFL) == 0) {
            res.observations.add(new ScanResult.Observation("SEQ_WRAP", coord,
                    "sequence wraps " + Hex.u32(expectedSeq) + " -> " + Hex.u32(0L)));
        }
        res.txns.add(txn);
        return true;
    }

    private static void checksumBreak(ScanResult res, byte[][] journal, int descCoord,
                                      long first, int ringSize, int checksumType,
                                      Integer horizon, long expectedSeq,
                                      String why, String prefix, ScanResult.Txn partialTxn) {
        if (partialTxn != null) {
            partialTxn.status = "UNCERTAIN";
            res.txns.add(partialTxn);
        }
        int resumeCoord = partialTxn == null ? descCoord
                : (partialTxn.commitCoord != null ? partialTxn.commitCoord
                : (partialTxn.revokeCoord != null ? partialTxn.revokeCoord : descCoord));
        resumeCoord = (int) first + ((resumeCoord - (int) first + 1) % ringSize);

        ScanResult.Anomaly a = new ScanResult.Anomaly(
                prefix + "/csum-" + Hex.u32(expectedSeq), "CHECKSUM_BREAK", descCoord,
                expectedSeq, why + "; the boundary after this checksum failure is uncertain");
        a.candidates.add(new ScanResult.Candidate("stop-boundary",
                "log ends before transaction " + Hex.u32(expectedSeq),
                "rule: checksum failure invalidates this transaction; the journal is "
                        + "treated as ending at journal#" + descCoord
                        + " (equivalent to jbd2 refusing torn records)"));
        a.candidates.add(new ScanResult.Candidate("torn-continue",
                "torn record skipped, later transactions scanned from journal#" + resumeCoord,
                "rule: keep the failed transaction incomplete but continue the ring walk; "
                        + "later complete commits remain legal replay candidates"));
        res.anomalies.add(a);
        res.endOfLog = descCoord;
        res.continuation = scanContinuation(journal, first, ringSize, checksumType, horizon,
                resumeCoord, SeqNum.next(expectedSeq));
    }

    /** The legal ring continuation used by candidate "torn-continue". */
    private static ScanResult scanContinuation(byte[][] journal, long first, int ringSize,
                                               int checksumType, Integer horizon,
                                               int startPos, long seq) {
        ScanResult cont = new ScanResult();
        int pos = startPos;
        for (int steps = 0; steps < ringSize; steps++) {
            if (horizon != null && pos == horizon) {
                cont.observations.add(new ScanResult.Observation("CRASH_HORIZON", pos,
                        "continuation stops at crash horizon"));
                break;
            }
            JournalBlock blk = readBlock(journal, pos, checksumType);
            if (blk == null || blk.type != Jbd2.TYPE_DESCRIPTOR
                    || SeqNum.compare(blk.sequence, seq) != 0) {
                cont.observations.add(new ScanResult.Observation("END_OF_LOG", pos,
                        "continuation ends here"));
                break;
            }
            if (blk.checksumOk != null && !blk.checksumOk) {
                cont.observations.add(new ScanResult.Observation("CHECKSUM_BREAK", pos,
                        "another checksum failure in continuation; boundary stays uncertain"));
                break;
            }
            ScanResult.Txn t = new ScanResult.Txn(blk.sequence, pos);
            blk.tags.forEach(tag -> t.fsBlocks.add(tag.fsBlock));
            cont.ringWalk.add(pos);
            int p = pos;
            boolean cut = false;
            for (int i = 0; i < blk.tags.size(); i++) {
                p = (int) first + ((p - (int) first + 1) % ringSize);
                if (horizon != null && p == horizon) { cut = true; break; }
                t.dataCoords.add(p);
                cont.ringWalk.add(p);
            }
            if (cut) {
                t.status = "INCOMPLETE";
                cont.txns.add(t);
                break;
            }
            JournalBlock nxt = readBlock(journal, p, checksumType);
            if (nxt != null && nxt.type == Jbd2.TYPE_REVOKE
                    && SeqNum.compare(nxt.sequence, seq) == 0) {
                if (nxt.checksumOk != null && !nxt.checksumOk) break;
                t.revokeCoord = p;
                t.revokeBlocks = new ArrayList<>(nxt.revokeBlocks);
                cont.ringWalk.add(p);
                p = (int) first + ((p - (int) first + 1) % ringSize);
                nxt = readBlock(journal, p, checksumType);
            }
            if (nxt == null || nxt.type != Jbd2.TYPE_COMMIT
                    || SeqNum.compare(nxt.sequence, seq) != 0
                    || (nxt.checksumOk != null && !nxt.checksumOk)) {
                t.status = "INCOMPLETE";
                cont.txns.add(t);
                break;
            }
            t.commitCoord = p;
            t.status = "COMMITTED";
            cont.ringWalk.add(p);
            cont.txns.add(t);
            seq = SeqNum.next(seq);
            pos = (int) first + ((p - (int) first + 1) % ringSize);
        }
        return cont;
    }
}
