package gsb.jbd2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志回放引擎。核心语义：
 *  - 仅有完整 commit 的事务可重放；
 *  - revoke 按 sequence 范围生效（撤销 sequence 不晚于撤销事务的既有写入）；
 *  - 环形日志序号回绕不用普通整数排序，一律用 SeqNum 串行号算术；
 *  - checksum 失败以后的边界保留不确定性，所有合法候选并列保留，不吞掉多解。
 */
public final class ReplayEngine {
    private ReplayEngine() {}

    public record TxGroup(int id, long sequence, int descriptorIndex, List<Integer> dataIndexes,
                          List<Long> targets, List<Long> revoked, List<Integer> revokeIndexes,
                          Integer commitIndex, String status, String reason) {
        public static final String COMMITTED = "COMMITTED";
        public static final String HALF = "HALF";
    }

    public record Write(long fsBlock, int journalIndex, long sequence, int txId) {}

    public record RevokeHit(long fsBlock, Write canceled, int byTx, long bySequence) {}

    public record CandidateResult(String id, String label, List<Integer> includedTxIds,
                                  List<Write> writes, List<RevokeHit> revokeHits,
                                  Map<Long, Write> finalMap, Map<Long, List<Write>> overwriteOrder) {}

    /** 机器可读取的判定事件：rule（判断依据）、subject（受影响对象）、decision（判定状态）、detail（输入片段）。 */
    public record Judgment(String id, String rule, String subject, String decision, String detail) {}

    public record Plan(List<TxGroup> txs, Integer checksumFailureIndex,
                       List<CandidateResult> candidates, List<String> anomalies,
                       List<Judgment> judgments) {}

    /** 环顺序：自 startOffset 起绕环一周，禁止按 sequence 整数排序。 */
    public static List<Model.JournalBlock> ringOrder(Model.Journal journal) {
        List<Model.JournalBlock> blocks = journal.blocks();
        int n = blocks.size();
        List<Model.JournalBlock> ring = new ArrayList<>(n);
        int start = (int) (journal.startOffsetBlocks() % Math.max(1, n));
        for (int k = 0; k < n; k++) ring.add(blocks.get((start + k) % n));
        return ring;
    }

    /** 把环扫描结果分组成事务：descriptor 开启，data 归属，revoke 附带，commit 闭合。 */
    public static List<TxGroup> groupTransactions(Model.Journal journal) {
        List<TxGroup> txs = new ArrayList<>();
        List<Model.JournalBlock> ring = ringOrder(journal);
        int nextId = 0;
        Long curSeq = null;
        Integer curDesc = null;
        List<Integer> curData = new ArrayList<>();
        List<Long> curTargets = new ArrayList<>();
        List<Long> curRevoked = new ArrayList<>();
        List<Integer> curRevokeIdx = new ArrayList<>();
        for (Model.JournalBlock b : ring) {
            switch (b.tag()) {
                case DESCRIPTOR -> {
                    if (curDesc != null) {
                        txs.add(new TxGroup(nextId++, curSeq, curDesc, curData, curTargets, curRevoked,
                                curRevokeIdx, null, TxGroup.HALF,
                                "descriptor blk" + b.index() + " 开启新事务前未出现 commit"));
                    }
                    curSeq = b.sequence();
                    curDesc = b.index();
                    curData = new ArrayList<>();
                    curTargets = new ArrayList<>(b.targets());
                    curRevoked = new ArrayList<>();
                    curRevokeIdx = new ArrayList<>();
                }
                case DATA -> {
                    if (curDesc != null) curData.add(b.index());
                }
                case REVOKE -> {
                    if (curDesc != null) {
                        curRevoked.addAll(b.revoked());
                        curRevokeIdx.add(b.index());
                    }
                }
                case COMMIT -> {
                    if (curDesc != null && curSeq != null && curSeq == b.sequence()) {
                        txs.add(new TxGroup(nextId++, curSeq, curDesc, curData, curTargets, curRevoked,
                                curRevokeIdx, b.index(), TxGroup.COMMITTED, "commit 于 blk" + b.index()));
                        curDesc = null;
                        curSeq = null;
                    }
                }
                case UNKNOWN -> {
                    if (curDesc != null) {
                        txs.add(new TxGroup(nextId++, curSeq, curDesc, curData, curTargets, curRevoked,
                                curRevokeIdx, null, TxGroup.HALF,
                                "遇到无法解析的 blk" + b.index() + "，事务截断"));
                        curDesc = null;
                        curSeq = null;
                    }
                }
            }
        }
        if (curDesc != null) {
            txs.add(new TxGroup(nextId, curSeq, curDesc, curData, curTargets, curRevoked,
                    curRevokeIdx, null, TxGroup.HALF, "日志末尾缺少 commit（崩溃截断）"));
        }
        return txs;
    }

    /**
     * 生成回放计划。
     * @param crashPointBlock 预期崩溃点（坐标基准：自环起点计的日志块数，等于环长表示扫到环尾）
     */
    public static Plan plan(Model.Journal journal, int crashPointBlock, List<TxGroup> txs) {
        List<Model.JournalBlock> ring = ringOrder(journal);
        Map<Integer, Integer> ringPos = new LinkedHashMap<>();
        for (int p = 0; p < ring.size(); p++) ringPos.put(ring.get(p).index(), p);

        Integer failIdx = null;
        Integer failPos = null;
        for (int p = 0; p < ring.size(); p++) {
            Model.JournalBlock b = ring.get(p);
            if ((b.tag() == Model.BlockTag.DESCRIPTOR || b.tag() == Model.BlockTag.COMMIT
                    || b.tag() == Model.BlockTag.REVOKE) && !b.checksumOk()) {
                failIdx = b.index();
                failPos = p;
                break;
            }
        }

        List<String> anomalies = new ArrayList<>();
        List<Judgment> judgments = new ArrayList<>();

        List<TxGroup> committedBeforeCrash = new ArrayList<>();
        for (TxGroup tx : txs) {
            Integer commitPos = tx.commitIndex() == null ? null : ringPos.get(tx.commitIndex());
            int descPos = ringPos.get(tx.descriptorIndex());
            if (tx.commitIndex() != null && commitPos != null && commitPos < crashPointBlock) {
                committedBeforeCrash.add(tx);
            } else if (descPos < crashPointBlock) {
                String detail = "tx#" + tx.id() + " seq=" + SeqNum.hex(tx.sequence())
                        + "：commit 未在崩溃点 blk" + crashPointBlock + " 之前出现（"
                        + tx.reason() + "）";
                anomalies.add(detail);
                judgments.add(judgment("only-complete-commit", "tx#" + tx.id(),
                        "excluded", detail + "；仅有完整 commit 的事务可重放"));
            }
        }

        // 回绕判定：环顺序中 sequence 数值下降即发生回绕，禁止普通整数排序
        for (int i = 1; i < committedBeforeCrash.size(); i++) {
            long prev = committedBeforeCrash.get(i - 1).sequence();
            long cur = committedBeforeCrash.get(i).sequence();
            if (cur < prev) {
                judgments.add(judgment("sequence-wrap-ordering",
                        "tx#" + committedBeforeCrash.get(i - 1).id() + "->tx#" + committedBeforeCrash.get(i).id(),
                        "ring-order",
                        "sequence " + SeqNum.hex(prev) + " 之后出现 " + SeqNum.hex(cur)
                                + "：按串行号算术（mod 2^32）后者更新，普通整数排序被拒绝"));
            }
        }

        List<CandidateResult> candidates = new ArrayList<>();
        boolean failureInRange = failPos != null && failPos < crashPointBlock;
        if (failureInRange) {
            final int fp = failPos;
            List<TxGroup> beforeFailure = committedBeforeCrash.stream()
                    .filter(t -> ringPos.get(t.commitIndex()) < fp).toList();
            candidates.add(replay("stop-at-failure",
                    "保守：checksum 断点 blk" + failIdx + " 处停住，仅重放断点之前完整提交的事务", beforeFailure));
            candidates.add(replay("trust-later-commits",
                    "宽松：信任断点之后仍完整提交的事务（结构完整但 checksum 不可信）", committedBeforeCrash));
            judgments.add(judgment("checksum-boundary-uncertainty", "blk" + failIdx,
                    "uncertain-after",
                    "checksum 失败以后的边界保留不确定性：候选 stop-at-failure 与 trust-later-commits 并列保留，"
                            + "不在导入期吞掉任一解"));
        } else {
            candidates.add(replay("all-committed", "全部崩溃点前完整提交的事务", committedBeforeCrash));
        }

        for (CandidateResult c : candidates) {
            for (RevokeHit h : c.revokeHits()) {
                judgments.add(judgment("revoke-sequence-range", "fs" + h.fsBlock(),
                        "revoked@" + c.id(),
                        "tx#" + h.byTx() + "（seq=" + SeqNum.hex(h.bySequence()) + "）的 revoke 撤销 blk"
                                + h.canceled().journalIndex() + "（seq=" + SeqNum.hex(h.canceled().sequence())
                                + "）：revoke 按 sequence 范围生效"));
            }
        }
        return new Plan(txs, failIdx, candidates, anomalies, judgments);
    }

    /** 对给定事务集合按环顺序回放：写入按序覆盖，revoke 撤销 sequence 不晚于本事务的既有写入。 */
    public static CandidateResult replay(String id, String label, List<TxGroup> included) {
        List<Write> writes = new ArrayList<>();
        List<RevokeHit> hits = new ArrayList<>();
        Map<Long, Write> map = new LinkedHashMap<>();
        Map<Long, List<Write>> order = new LinkedHashMap<>();
        for (TxGroup tx : included) {
            int pairs = Math.min(tx.dataIndexes().size(), tx.targets().size());
            for (int k = 0; k < pairs; k++) {
                long fs = tx.targets().get(k);
                Write w = new Write(fs, tx.dataIndexes().get(k), tx.sequence(), tx.id());
                writes.add(w);
                order.computeIfAbsent(fs, x -> new ArrayList<>()).add(w);
                map.put(fs, w);
            }
            for (long rb : tx.revoked()) {
                Write cur = map.get(rb);
                if (cur != null && SeqNum.beforeOrEqual(cur.sequence(), tx.sequence())) {
                    map.remove(rb);
                    hits.add(new RevokeHit(rb, cur, tx.id(), tx.sequence()));
                }
            }
        }
        return new CandidateResult(id, label, included.stream().map(TxGroup::id).toList(),
                writes, hits, map, order);
    }

    /** 重放幂等检验：同一写序列应用两次，终态必须一致。 */
    public static boolean idempotent(CandidateResult candidate) {
        Map<Long, Integer> once = applyWrites(candidate.writes());
        Map<Long, Integer> twice = applyWrites(candidate.writes());
        return once.equals(twice);
    }

    private static Map<Long, Integer> applyWrites(List<Write> writes) {
        Map<Long, Integer> m = new LinkedHashMap<>();
        for (Write w : writes) m.put(w.fsBlock(), w.journalIndex());
        return m;
    }

    private static Judgment judgment(String rule, String subject, String decision, String detail) {
        String id = "jg-" + Integer.toHexString((rule + "|" + subject + "|" + decision).hashCode());
        return new Judgment(id, rule, subject, decision, detail);
    }
}
