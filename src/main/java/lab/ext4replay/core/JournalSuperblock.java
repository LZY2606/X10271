package lab.ext4replay.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * ext4 日志 superblock（jbd2 journal_superblock_s 的实验室子集）。
 * 原始字节始终保留；解析失败的字段保留原值并附诊断，不做静默规范化。
 */
public final class JournalSuperblock {
    public static final long MAGIC = 0xC03B3998L;

    public final byte[] raw;            // 原始块字节，永不修改
    public final long sequence;         // s_sequence：下一个事务序号
    public final long start;            // s_start：日志环起点（块索引，日志坐标系）
    public final long blockSizeRaw;     // s_blocksize 原始值
    public final long maxlen;           // s_maxlen：日志总块数
    public final long first;            // s_first：日志数据区首块
    public final int checksumType;      // s_checksum_type：0=无(v2) 1=crc32c(v3)
    public final List<String> diagnostics = new ArrayList<>();

    private JournalSuperblock(byte[] raw, long sequence, long start, long blockSizeRaw,
                              long maxlen, long first, int checksumType) {
        this.raw = raw;
        this.sequence = sequence;
        this.start = start;
        this.blockSizeRaw = blockSizeRaw;
        this.maxlen = maxlen;
        this.first = first;
        this.checksumType = checksumType;
    }

    public static JournalSuperblock parse(byte[] raw) {
        List<String> diags = new ArrayList<>();
        JournalSuperblock sb;
        if (raw.length < 84) {
            sb = new JournalSuperblock(raw, 0, 0, 0, 0, 0, 0);
            sb.diagnostics.add("superblock 长度不足 84 字节: raw=" + raw.length + "B，无法解析字段");
            return sb;
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        long magic = b.getInt(0) & 0xFFFFFFFFL;
        long blocktype = b.getInt(4) & 0xFFFFFFFFL;
        sb = new JournalSuperblock(
                raw,
                b.getInt(24) & 0xFFFFFFFFL,
                b.getInt(28) & 0xFFFFFFFFL,
                b.getInt(12) & 0xFFFFFFFFL,
                b.getInt(16) & 0xFFFFFFFFL,
                b.getInt(20) & 0xFFFFFFFFL,
                raw[80] & 0xFF);
        if (magic != MAGIC) {
            sb.diagnostics.add(String.format("magic 不匹配: 期望 0x%08X 实际 0x%08X", MAGIC, magic));
        }
        if (blocktype != 3 && blocktype != 4) {
            sb.diagnostics.add("superblock blocktype 应为 3/4，实际 " + blocktype);
        }
        sb.validateBlockSize();
        return sb;
    }

    private void validateBlockSize() {
        if (!isBlockSizeValid()) {
            diagnostics.add("无效块大小: s_blocksize=" + blockSizeRaw
                    + "（合法值为 1024/2048/4096 且为 2 的幂）；保留原值，不做规范化");
        }
    }

    public boolean isBlockSizeValid() {
        return blockSizeRaw == 1024 || blockSizeRaw == 2048 || blockSizeRaw == 4096;
    }

    /** 派生层才允许使用的规范化块大小；无效时返回 -1 且原值仍在 blockSizeRaw。 */
    public long normalizedBlockSize() {
        return isBlockSizeValid() ? blockSizeRaw : -1;
    }
}
