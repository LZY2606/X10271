package lab.jbd2.model;

/**
 * 块覆盖顺序中的一个操作（重放计划原子步骤）。
 * 顺序由回绕序号决定，绝不用普通整数排序。
 */
public record CoverageOp(int ordinal, long sequence, String kind,
                         long fsBlocknr, long dataPhys, boolean mapped,
                         boolean revoked, boolean applied,
                         String reason, String rule) {
    public String stableKey() {
        return "op:" + Seq.hex(sequence) + ":" + ordinal + ":" + fsBlocknr + ":" + dataPhys;
    }
}
