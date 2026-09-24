package lab.jbd2/model;

/** revoke 命中：(revoke sequence Sr, 文件系统块号 B, 被取消写入的事务 S 与数据块坐标)。 */
public record RevokeHit(long revokeSeq, long fsBlocknr, long canceledSeq,
                        long dataPhys, String rule) {
    public String stableKey() {
        return "revoke:" + Seq.hex(revokeSeq) + ":" + fsBlocknr + ":" + Seq.hex(canceledSeq);
    }
}
