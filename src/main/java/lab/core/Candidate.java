package lab.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个合法回放候选。checksum 断点之后保留多个候选，不吞掉不确定性。
 * boundaryIndex 是候选生效的环边界（不含）。
 */
public final class Candidate {
    public final String id;
    public final int boundaryIndex;
    public final String reason;
    public final boolean certain;
    /** fsBlock -> 最终生效的写入 */
    public final Map<Long, WriteStep> finalWrites = new LinkedHashMap<>();
    /** fsBlock -> 覆盖顺序（含被取代/被撤销的步骤） */
    public final Map<Long, List<WriteStep>> coverage = new LinkedHashMap<>();

    public Candidate(String id, int boundaryIndex, String reason, boolean certain) {
        this.id = id;
        this.boundaryIndex = boundaryIndex;
        this.reason = reason;
        this.certain = certain;
    }
}
