package lab.core;

import java.util.ArrayList;
import java.util.List;

/** 一个事务：描述块开启，数据块跟随，commit 收尾；revoke 块归属同事务。 */
public final class TxGroup {
    public final long sequence;
    public final int descriptorIndex;
    public final List<JournalBlock> dataBlocks = new ArrayList<>();
    public final List<JournalBlock> revokeBlocks = new ArrayList<>();
    public JournalBlock commit;

    public TxGroup(long sequence, int descriptorIndex) {
        this.sequence = sequence;
        this.descriptorIndex = descriptorIndex;
    }

    public boolean committed() { return commit != null; }

    public int endIndex() {
        return commit != null ? commit.ringIndex() : descriptorIndex;
    }
}
