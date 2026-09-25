package lab.ext4replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 扫描出的一个事务：descriptor/data/commit/revoke 关系全部保留。 */
public final class ScannedTxn {
    public enum Certainty { CERTAIN, UNCERTAIN }

    public final String id;                 // 稳定身份，如 txn@j5
    public final long sequence;             // 32 位回绕序号
    public final int descriptorIndex;       // 描述块在日志环中的块索引（日志坐标系）
    public final List<Tag> tags = new ArrayList<>();
    /** fsBlock -> 数据块字节（原块字节，未规范化）。 */
    public final Map<Long, byte[]> dataBlocks = new LinkedHashMap<>();
    /** fsBlock -> 数据所在日志块索引。 */
    public final Map<Long, Integer> dataJournalIndex = new LinkedHashMap<>();
    public final List<Long> revokedBlocks = new ArrayList<>();
    public int revokeJournalIndex = -1;
    public int commitJournalIndex = -1;
    public boolean committed;
    public boolean commitChecksumOk = true;
    public Certainty certainty = Certainty.CERTAIN;
    public final List<String> notes = new ArrayList<>();

    public ScannedTxn(String id, long sequence, int descriptorIndex) {
        this.id = id;
        this.sequence = sequence;
        this.descriptorIndex = descriptorIndex;
    }

    public boolean replayable() {
        // 语义：仅有完整 commit 且 checksum 通过的事务可重放。
        return committed && commitChecksumOk;
    }
}
