package lab.jbd2.model;

import java.util.List;

/**
 * 环中一个物理块的扫描视图：
 * header 为 null 表示数据块或空块；kind 派生自头与上下文。
 */
public final class JournalBlock {
    public enum Kind { SUPERBLOCK, DESCRIPTOR, COMMIT, REVOKE, DATA, ZERO, FREE }

    public final long phys;                       // 物理块号（坐标，单位 block）
    public final Kind kind;
    public final BlockHeader header;              // descriptor/commit/revoke 非空
    public final List<DescriptorTag> tags;        // 仅 descriptor
    public final List<Long> revokedBlocknrs;      // 仅 revoke（文件系统块号）
    public final byte[] bytes;                    // 原始字节（含头，整块保留）

    public JournalBlock(long phys, Kind kind, BlockHeader header,
                        List<DescriptorTag> tags, List<Long> revoked, byte[] bytes) {
        this.phys = phys;
        this.kind = kind;
        this.header = header;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
        this.revokedBlocknrs = revoked == null ? List.of() : List.copyOf(revoked);
        this.bytes = bytes;
    }

    public long sequenceOr(long fallback) {
        return header != null ? header.sequence() : fallback;
    }
}
