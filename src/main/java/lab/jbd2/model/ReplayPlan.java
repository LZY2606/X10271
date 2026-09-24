package lab.jbd2.model;

import java.util.List;

/**
 * 完整派生结果：扫描 + 崩溃点切片 + 重放计划 + 派生块图 + 裁定事件。
 * 这是一次独立推导的产物（对应 store 中的一个 version）。
 */
public final class ReplayPlan {
    public String planId;
    public String caseId;
    public String variantId;                 // 基线为 "baseline"
    public String crashPointRaw;
    public NormalizedParams params;
    public JournalSuperblock superblock;
    public List<JournalBlock> ring;          // 按环物理顺序
    public List<TransactionGroup> groups;    // 按回绕序号
    public List<CoverageOp> coverage;        // 块覆盖顺序
    public List<RevokeHit> revokeHits;       // revoke 命中
    public List<DerivedBlock> derivedBlocks; // 崩溃点派生块图
    public List<Judgment> judgments;         // 机器可读裁定
    public List<Diagnostic> diagnostics;
    public List<LegalCandidate> candidates;  // 无法唯一决定时的全部合法候选
    public boolean idempotent;               // 重放幂等检验结果
    public String idempotencyDetail;

    public static final class NormalizedParams {
        public long blockSize;               // bytes（派生层规范化）
        public String blockSizeRaw;
        public long ringFirst, ringStart, ringMaxlen;
        public long checksumType;
        public long crashSeq;               // 崩溃点截断序号（含）
        public boolean blockSizeValid;
        public String blockSizeUnit;
    }
}
