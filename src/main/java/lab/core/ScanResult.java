package lab.core;

import java.util.ArrayList;
import java.util.List;

/** 一次日志扫描 + 回放计划的完整结果。 */
public final class ScanResult {
    public final List<JournalBlock> blocks;
    public final List<TxGroup> transactions = new ArrayList<>();
    public final List<Judgment> judgments = new ArrayList<>();
    public final List<Candidate> candidates = new ArrayList<>();
    /** checksum 失败位置（无则 -1）。 */
    public int checksumBreakIndex = -1;
    /** 确定前缀边界：最后一个 checksum 完好的 commit 之后（不含）。 */
    public int certainBoundary = 0;

    public ScanResult(List<JournalBlock> blocks) { this.blocks = blocks; }

    public Candidate certainCandidate() {
        return candidates.stream().filter(c -> c.certain).findFirst().orElse(null);
    }
}
