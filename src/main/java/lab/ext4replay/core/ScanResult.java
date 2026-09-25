package lab.ext4replay.core;

import java.util.ArrayList;
import java.util.List;

public final class ScanResult {
    public final JournalSuperblock superblock;
    /** 按日志环物理顺序（从 s_start 起），绝不按序号排序。 */
    public final List<ScannedTxn> transactions = new ArrayList<>();
    public final List<String> diagnostics = new ArrayList<>();
    /** 第一个 checksum 失败点之后的事务全部标为 UNCERTAIN；-1 表示无断点。 */
    public int checksumBreakTxnIndex = -1;
    public boolean blockSizeValid = true;

    public ScanResult(JournalSuperblock superblock) {
        this.superblock = superblock;
    }
}
