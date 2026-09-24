package gsb.jbd2;

import java.util.List;

/** 领域模型：所有原始表示（原块字节、块大小、环起点、sequence、checksum 版本）均保留。 */
public final class Model {
    private Model() {}

    public enum BlockTag { DESCRIPTOR, DATA, COMMIT, REVOKE, UNKNOWN }

    /**
     * @param blockSizeRaw        块大小原始表示（导入时原样保留，不做规范化）
     * @param blockSizeUnit       块大小单位
     * @param blockSizeBytes      派生层规范化结果；无法转换时为 null
     * @param blockSizeDiagnostic 无法转换时的诊断（值留在原位置，不为画图丢弃）
     * @param journalStartBlock   日志所在文件系统块号（坐标基准：fs 块）
     * @param firstSequence       日志环起始 sequence（32 位，回绕语义）
     * @param checksumVersion     JBD2 checksum 版本（2 或 3）
     * @param rawHex              superblock 原字节
     */
    public record Superblock(
            String blockSizeRaw,
            String blockSizeUnit,
            Long blockSizeBytes,
            String blockSizeDiagnostic,
            long journalStartBlock,
            long firstSequence,
            int checksumVersion,
            String rawHex) {

        public static boolean validBlockSize(long v) {
            return v >= 1024 && v <= 65536 && (v & (v - 1)) == 0;
        }
    }

    /**
     * @param index       环内槽位（坐标基准：自环起点计的日志块序号）
     * @param tag         descriptor / data / commit / revoke / unknown
     * @param sequence    32 位无符号 sequence，按回绕语义比较
     * @param targets     descriptor 声明的 fs 目标块号
     * @param revoked     revoke 块声明撤销的 fs 块号
     * @param checksumOk  checksum 校验结果（按 superblock 的 checksum 版本）
     */
    public record JournalBlock(
            int index,
            BlockTag tag,
            long sequence,
            List<Long> targets,
            List<Long> revoked,
            boolean checksumOk,
            String checksumDetail,
            String rawHex,
            String diagnostic) {}

    public record Journal(long startOffsetBlocks, List<JournalBlock> blocks) {}
}
