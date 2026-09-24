package lab.jbd2.model;

import java.util.List;

/**
 * JBD2 日志 superblock（本实验的离线模型，字段与 Linux jbd2 头对齐）。
 * 原始字节整块保留；派生字段携带坐标/单位与诊断。
 */
public final class JournalSuperblock {
    public static final int MAGIC = 0xc03b3998;

    public final byte[] raw;
    public final Raw<Long> first;          // s_first: 环起始物理块号（块）
    public final Raw<Long> start;          // s_start: 日志头下一可用块（块）
    public final Raw<Long> maxlen;         // s_maxlen: 环容量（块）
    public final Raw<Long> firstCommit;    // s_first_commit_id（sequence 基准）
    public final Raw<Long> checksumType;   // 偏移 256 的 checksum 类型（见 Checksums）
    public final Raw<Long> errno;
    public final boolean magicOk;
    public final List<String> diagnostics;

    public JournalSuperblock(byte[] raw, Raw<Long> first, Raw<Long> start, Raw<Long> maxlen,
                             Raw<Long> firstCommit, Raw<Long> checksumType, Raw<Long> errno,
                             boolean magicOk, List<String> diagnostics) {
        this.raw = raw;
        this.first = first;
        this.start = start;
        this.maxlen = maxlen;
        this.firstCommit = firstCommit;
        this.checksumType = checksumType;
        this.errno = errno;
        this.magicOk = magicOk;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<String> allDiagnostics() {
        java.util.ArrayList<String> d = new java.util.ArrayList<>(diagnostics);
        for (Raw<?> r : List.of(first, start, maxlen, firstCommit, checksumType, errno)) {
            d.addAll(r.diagnostics);
        }
        if (!magicOk) d.add("journal superblock magic != 0xc03b3998");
        return List.copyOf(d);
    }
}
