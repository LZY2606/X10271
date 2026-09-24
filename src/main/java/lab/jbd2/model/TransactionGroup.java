package lab.jbd2.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个事务组（descriptor ... data ... [revoke] ... commit）。
 * 状态机：
 *   COMMITTED     —— descriptor + commit 齐全（但是否可重放仍取决于 checksum）
 *   HALF          —— 有 descriptor 无 commit（崩溃点截断）
 *   ORPHAN_TAIL   —— 环尾无归属的块（扫描边界不确定）
 */
public final class TransactionGroup {
    public enum Status { COMMITTED, HALF, ORPHAN_TAIL }

    public final long sequence;
    public final Status status;
    public final long descriptorPhys;
    public final List<Long> blockPhys;           // 事务覆盖的全部物理块（按环顺序）
    public final List<DescriptorTag> tags;
    public final List<JournalBlock> revokes;
    public final Long commitPhys;
    public final long firstPhys, lastPhys;        // 环上区间（可能跨回绕点）
    public final boolean wraps;
    public final ChecksumVerdict checksum;

    public TransactionGroup(long sequence, Status status, long descriptorPhys,
                            List<Long> blockPhys, List<DescriptorTag> tags,
                            List<JournalBlock> revokes, Long commitPhys,
                            long firstPhys, long lastPhys, boolean wraps,
                            ChecksumVerdict checksum) {
        this.sequence = sequence;
        this.status = status;
        this.descriptorPhys = descriptorPhys;
        this.blockPhys = List.copyOf(blockPhys);
        this.tags = List.copyOf(tags);
        this.revokes = List.copyOf(revokes);
        this.commitPhys = commitPhys;
        this.firstPhys = firstPhys;
        this.lastPhys = lastPhys;
        this.wraps = wraps;
        this.checksum = checksum;
    }

    /** 只有 COMMITTED 且 checksum 通过才是候选可重放（最终还受崩溃点与 revoke 约束）。 */
    public boolean replayable() {
        return status == Status.COMMITTED && checksum.ok;
    }

    public List<Long> revokesFlat() {
        List<Long> out = new ArrayList<>();
        for (JournalBlock rb : revokes) out.addAll(rb.revokedBlocknrs);
        return out;
    }
}
