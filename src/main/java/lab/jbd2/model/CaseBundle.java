package lab.jbd2.model;

import java.util.List;
import java.util.Map;

/**
 * 离线案例包（领域材料，不是通用表格）。
 * 所有量保留原始表示 + 单位 + 坐标基准；派生层才规范化。
 */
public final class CaseBundle {
    public String format = "jbd2-lab-case/1";
    public String caseId;
    public String title;
    public String narrative;

    /** 全局参数的原始表示（值/单位/坐标三件套）。 */
    public Map<String, RawParam> params;

    /** 物理块：键为物理块号的原始表示，值为十六进制原块字节。 */
    public List<RawBlockEntry> blocks;

    /** superblock 原始材料（与物理块字节同源，便于对照）。 */
    public String journalSuperblockHex;
    public String extSuperblockHex;

    /** 预期崩溃点的原始表示（可空）。 */
    public RawParam crashPoint;

    /** 反例档案：每个变体只改动一个直接来源。 */
    public List<Variant> variants;

    /** 九十秒演示脚本（叙事步骤，不拆成无关按钮）。 */
    public List<DemoStep> demoScript;

    public static final class RawParam {
        public String raw;
        public String unit;
        public String coordinate;
        public String note;
    }

    public static final class RawBlockEntry {
        public String physRaw;     // 物理块号的原始表示
        public String unit;
        public String coordinate;
        public String hex;
        public String role;        // journal|fsbase|j-sb|fs-sb
    }

    public static final class Variant {
        public String id;
        public String title;
        public String scenario;    // wrap|half|duplicate|revoke-rewrite|checksum|blocksize|idempotent
        public String changedSource;        // 被改动的直接来源（稳定身份）
        public String changeDescription;
        public Map<String, String> paramOverrides;
        public List<BlockOverride> blockOverrides;
        public String expectation;
        public List<ExpectedJudgment> expectedJudgments;
    }

    public static final class BlockOverride {
        public String physRaw;
        public String hex;        // null = 截断（半事务/崩溃点）
        public Boolean truncate;  // true 时从该块起截断日志
    }

    public static final class ExpectedJudgment {
        public String rule;
        public String outcome;
        public String detail;
    }

    public static final class DemoStep {
        public String id;
        public String title;
        public String body;
        public String action;     // view|crash|variant|compare|accept|reject
        public String target;
    }
}
