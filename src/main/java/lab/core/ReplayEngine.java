package lab.core;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志扫描与回放计划引擎。语义红线：
 *  - 仅有完整 commit 的事务可重放；
 *  - revoke 按 sequence 范围生效（回绕感知）；
 *  - 环形日志序号回绕不用普通整数排序（物理环顺序为准）；
 *  - checksum 失败以后的边界保留不确定性，全部合法候选都保留。
 */
public final class ReplayEngine {

    public static final String RULE_VERSION = "replay-v1";

    public ScanResult scan(byte[] image, int blockSize, String checksumVersion, CrashPoint crash) {
        List<Judgment> pre = new ArrayList<>();
        if (blockSize != 1024 && blockSize != 2048 && blockSize != 4096) {
            ScanResult r = new ScanResult(List.of());
            r.judgments.add(Judgment.of("block-size.valid", "superblock/blockSize", "reject",
                    "无效块大小 " + blockSize + "；ext4 仅允许 1024/2048/4096，拒绝生成回放计划"));
            return r;
        }
        if (image.length % blockSize != 0) {
            ScanResult r = new ScanResult(List.of());
            r.judgments.add(Judgment.of("journal.length-aligned", "journal/image", "reject",
                    "镜像长度 " + image.length + " 不是块大小 " + blockSize + " 的整数倍"));
            return r;
        }
        List<JournalBlock> blocks = JournalImage.scanAll(image, blockSize, checksumVersion);
        ScanResult result = new ScanResult(blocks);
        int boundary = Math.min(crash.boundaryIndex(), blocks.size());

        // 1) checksum 边界：第一个校验失败块即确定前缀的终点
        int breakAt = -1;
        for (int i = 0; i < boundary; i++) {
            if (!blocks.get(i).checksumOk()) { breakAt = i; break; }
        }
        result.checksumBreakIndex = breakAt;
        int scannable = breakAt >= 0 ? breakAt : boundary;
        if (breakAt >= 0) {
            result.judgments.add(Judgment.of("checksum.boundary", "ring/" + breakAt, "uncertain",
                    "ringIndex=" + breakAt + " checksum 失败（version=" + checksumVersion
                            + "）；其后边界保留不确定性，候选不合并"));
        }

        // 2) 主扫描（确定前缀）
        List<TxGroup> txs = groupTransactions(blocks, 0, scannable, result.judgments);
        result.transactions.addAll(txs);
        int certain = certainBoundary(txs, scannable);
        result.certainBoundary = certain;

        // 3) 候选：确定前缀 + checksum 断点后的合法延续
        Candidate certainCand = buildCandidate("cand-certain", certain, "确定前缀：最后完整 commit 之前", true, txs, result.judgments);
        result.candidates.add(certainCand);
        if (breakAt >= 0) {
            // 合法候选 B：假设损坏块为孤立的写中断垃圾，跳过后继续扫描
            List<Judgment> optJudgments = new ArrayList<>();
            List<TxGroup> optTxs = groupTransactions(blocks, breakAt + 1, boundary, optJudgments);
            if (!optTxs.isEmpty()) {
                List<TxGroup> merged = new ArrayList<>(txs);
                merged.addAll(optTxs);
                int optBoundary = certainBoundary(optTxs, boundary);
                Candidate opt = buildCandidate("cand-skip-corrupt", Math.max(optBoundary, certain),
                        "跳过 checksum 失败块 " + breakAt + " 后继续扫描的延续", false, merged, optJudgments);
                result.candidates.add(opt);
                result.judgments.addAll(optJudgments);
                result.judgments.add(Judgment.of("checksum.boundary.uncertain", "ring/" + breakAt,
                        "keep-candidates", "checksum 失败后保留 " + result.candidates.size() + " 个合法候选，不做唯一化"));
            }
        }
        return result;
    }

    private int certainBoundary(List<TxGroup> txs, int fallback) {
        int b = 0;
        for (TxGroup t : txs) if (t.committed()) b = Math.max(b, t.endIndex() + 1);
        return b == 0 ? fallback == 0 ? 0 : b : b;
    }

    /** 按物理环顺序分组事务；序号回绕只记录证据，绝不按整数排序。 */
    private List<TxGroup> groupTransactions(List<JournalBlock> blocks, int from, int to, List<Judgment> judgments) {
        List<TxGroup> txs = new ArrayList<>();
        TxGroup open = null;
        int tagCursor = 0;
        Long prevSeq = null;
        for (int i = from; i < to; i++) {
            JournalBlock b = blocks.get(i);
            if (prevSeq != null && b.type() != BlockType.DATA && RingOrder.before(b.sequence(), prevSeq)) {
                judgments.add(Judgment.of("ring.wrap", "ring/" + i, "physical-order",
                        "sequence 从 " + prevSeq + " 回绕到 " + b.sequence()
                                + "；按物理环顺序排列，不用普通整数排序"));
            }
            if (b.type() != BlockType.DATA) prevSeq = b.sequence();
            switch (b.type()) {
                case DESCRIPTOR -> {
                    if (open != null) {
                        judgments.add(Judgment.of("tx.incomplete", "tx/" + open.sequence, "drop",
                                "描述块 ringIndex=" + open.descriptorIndex + " 的事务缺少 commit，不可重放"));
                    }
                    open = new TxGroup(b.sequence(), i);
                    txs.add(open);
                    tagCursor = 0;
                }
                case DATA -> {
                    if (open == null) {
                        judgments.add(Judgment.of("data.orphan", "ring/" + i, "drop",
                                "数据块没有所属事务，忽略"));
                    } else {
                        open.dataBlocks.add(b);
                        tagCursor++;
                    }
                }
                case COMMIT -> {
                    if (open == null) {
                        judgments.add(Judgment.of("commit.orphan", "ring/" + i, "drop",
                                "commit 块没有打开的事务"));
                    } else if (open.sequence != b.sequence()) {
                        judgments.add(Judgment.of("tx.sequence-mismatch", "tx/" + open.sequence, "drop",
                                "commit sequence=" + b.sequence() + " 与事务 sequence=" + open.sequence + " 不一致"));
                        open = null;
                    } else {
                        open.commit = b;
                        open = null;
                    }
                }
                case REVOKE -> {
                    if (open != null) open.revokeBlocks.add(b);
                    else {
                        // revoke 归属同 sequence 的最近事务
                        TxGroup owner = null;
                        for (int k = txs.size() - 1; k >= 0; k--) {
                            if (txs.get(k).sequence == b.sequence()) { owner = txs.get(k); break; }
                        }
                        if (owner != null) owner.revokeBlocks.add(b);
                        else judgments.add(Judgment.of("revoke.orphan", "ring/" + i, "keep",
                                "revoke 块找不到同事务，仍按 sequence 范围独立生效"));
                    }
                }
                default -> judgments.add(Judgment.of("block.unknown", "ring/" + i, "skip",
                        "未知块类型 " + b.type()));
            }
        }
        if (open != null) {
            judgments.add(Judgment.of("tx.incomplete", "tx/" + open.sequence, "drop",
                    "扫描结束时事务 sequence=" + open.sequence + " 仍无 commit（半事务），不可重放"));
        }
        return txs;
    }

    private Candidate buildCandidate(String id, int boundary, String reason, boolean certain,
                                     List<TxGroup> txs, List<Judgment> judgments) {
        Candidate c = new Candidate(id, boundary, reason, certain);
        // revoke 表：fsBlock -> 最大 revoke sequence（回绕窗口内）
        Map<Long, Long> revokeSeq = new HashMap<>();
        for (TxGroup t : txs) {
            if (!t.committed() || t.endIndex() >= boundary) continue;
            for (JournalBlock rb : t.revokeBlocks) {
                if (rb.revokedBlocks() == null) continue;
                for (Long fb : rb.revokedBlocks()) {
                    Long cur = revokeSeq.get(fb);
                    if (cur == null || RingOrder.before(cur, rb.sequence())) revokeSeq.put(fb, rb.sequence());
                }
            }
        }
        // 覆盖顺序 + 最终写入
        TxGroup openCtx = null;
        for (TxGroup t : txs) {
            if (!t.committed() || t.endIndex() >= boundary) continue;
            List<DescriptorTag> tags = t.dataBlocks.isEmpty() ? List.of()
                    : blockTags(t, txs);
            int cursor = 0;
            for (JournalBlock d : t.dataBlocks) {
                if (d.ringIndex() >= boundary) continue;
                long fsBlock = cursor < tags.size() ? tags.get(cursor).fsBlock() : -1;
                cursor++;
                if (fsBlock < 0) {
                    judgments.add(Judgment.of("data.no-tag", "ring/" + d.ringIndex(), "drop",
                            "数据块缺少对应描述标签"));
                    continue;
                }
                Long rev = revokeSeq.get(fsBlock);
                boolean cancelled = rev != null && !RingOrder.after(t.sequence, rev);
                List<WriteStep> steps = c.coverage.computeIfAbsent(fsBlock, k -> new ArrayList<>());
                if (!steps.isEmpty()) {
                    WriteStep prev = steps.get(steps.size() - 1);
                    if (!prev.superseded() && prev.cancelledByRevokeSeq() == null) {
                        steps.set(steps.size() - 1, new WriteStep(prev.fsBlock(), prev.sequence(),
                                prev.ringIndex(), prev.payloadHash(), true, null));
                        judgments.add(Judgment.of("block.duplicate", "fsblock/" + fsBlock,
                                "last-wins", "fs 块 " + fsBlock + " 被重复写入（ringIndex "
                                        + prev.ringIndex() + " -> " + d.ringIndex() + "），后者覆盖前者"));
                    }
                }
                WriteStep step = new WriteStep(fsBlock, t.sequence, d.ringIndex(),
                        sha256(d.payload()), false, cancelled ? rev : null);
                steps.add(step);
                if (cancelled) {
                    judgments.add(Judgment.of("revoke.hit", "fsblock/" + fsBlock, "cancel",
                            "sequence=" + t.sequence + " 对 fs 块 " + fsBlock + " 的写入被 revoke sequence="
                                    + rev + " 按 sequence 范围撤销（revoke 后重写才生效）"));
                } else {
                    c.finalWrites.put(fsBlock, step);
                }
            }
        }
        // 被撤销后没有更新的写入：从 finalWrites 移除
        c.finalWrites.entrySet().removeIf(e -> {
            List<WriteStep> steps = c.coverage.get(e.getKey());
            WriteStep last = steps.get(steps.size() - 1);
            return last.cancelledByRevokeSeq() != null;
        });
        return c;
    }

    private List<DescriptorTag> blockTags(TxGroup t, List<TxGroup> txs) {
        // 描述块在事务列表中按 ringIndex 唯一；从 txs 所在扫描无法直接取块，这里由调用方保证
        // TxGroup 不持有描述块引用，因此 tags 通过 descriptorIndex 外部解析。
        return DescriptorTagRegistry.tags(t.descriptorIndex);
    }

    /** 应用候选到基础块图。重放是幂等的：绝对写入，重复应用结果相同。 */
    public Map<Long, byte[]> apply(Candidate c, List<JournalBlock> blocks, Map<Long, byte[]> base) {
        Map<Long, byte[]> out = new LinkedHashMap<>(base);
        for (Map.Entry<Long, WriteStep> e : c.finalWrites.entrySet()) {
            WriteStep s = e.getValue();
            JournalBlock b = blocks.get(s.ringIndex());
            out.put(e.getKey(), b.payload());
        }
        return out;
    }

    /** 幂等检验：对同一候选连续应用两次，结果必须一致。 */
    public Judgment idempotencyCheck(Candidate c, List<JournalBlock> blocks, Map<Long, byte[]> base) {
        Map<Long, byte[]> once = apply(c, blocks, base);
        Map<Long, byte[]> twice = apply(c, blocks, once);
        boolean ok = once.equals(twice);
        return Judgment.of("replay.idempotent", c.id, ok ? "pass" : "fail",
                ok ? "重放幂等：二次应用结果一致（" + once.size() + " 块）"
                        : "重放非幂等：二次应用产生不同块图");
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 描述块标签注册表：扫描时填充，候选构建时查询。 */
    public static final class DescriptorTagRegistry {
        private static final Map<Integer, List<DescriptorTag>> TAGS = new HashMap<>();
        public static void register(int descriptorIndex, List<DescriptorTag> tags) {
            TAGS.put(descriptorIndex, tags);
        }
        public static List<DescriptorTag> tags(int descriptorIndex) {
            return TAGS.getOrDefault(descriptorIndex, List.of());
        }
        public static void clear() { TAGS.clear(); }
    }
}
