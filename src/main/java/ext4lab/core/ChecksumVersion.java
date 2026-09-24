package ext4lab.core;

/**
 * 日志校验和版本。v2 只校验 commit 块；v3（metadata_csum 风格）
 * 同时校验 descriptor 与 commit。校验失败不抛异常，而是形成
 * “checksum 断点”，由回放层保留边界之后的不确定性。
 */
public enum ChecksumVersion {
    V2, V3;

    public static ChecksumVersion parse(String raw) {
        if (raw == null) return V2;
        return switch (raw.trim().toLowerCase()) {
            case "v3", "3", "metadata_csum" -> V3;
            default -> V2;
        };
    }

    /** descriptor 校验：仅 v3 可校验；v2 返回 null 表示无法判定。 */
    public Boolean verifyDescriptor(byte[] block) {
        if (this != V3) return null;
        int n = block.length;
        long stored = Jbd.be32(block, n - 4);
        byte[] copy = block.clone();
        Jbd.putBe32(copy, n - 4, 0);
        return Jbd.crc32c(copy, 0, n) == stored;
    }

    /** commit 校验：v2/v3 均校验 offset 12 处的 crc32c。 */
    public boolean verifyCommit(byte[] block) {
        long stored = Jbd.be32(block, 12);
        byte[] copy = block.clone();
        Jbd.putBe32(copy, 12, 0);
        return Jbd.crc32c(copy, 0, block.length) == stored;
    }

    /** 计算并写入 descriptor 校验和（v3）。 */
    public static void sealDescriptor(byte[] block) {
        int n = block.length;
        Jbd.putBe32(block, n - 4, 0);
        Jbd.putBe32(block, n - 4, Jbd.crc32c(block, 0, n));
    }

    /** 计算并写入 commit 校验和。 */
    public static void sealCommit(byte[] block) {
        Jbd.putBe32(block, 12, 0);
        Jbd.putBe32(block, 12, Jbd.crc32c(block, 0, block.length));
    }
}
