package gsb.jbd2;

import java.util.zip.CRC32C;

/**
 * JBD2 checksum（教学用简化格式）：
 *  - v2：仅覆盖块首 64 字节（头部 + 标签区前段）
 *  - v3：覆盖整块（末 4 字节存储的 checksum 除外）
 * 存储位置固定为块末 4 字节（大端）。
 */
public final class Checksums {
    private Checksums() {}

    public static long compute(int version, byte[] block) {
        CRC32C crc = new CRC32C();
        if (version == 2) {
            crc.update(block, 0, Math.min(64, Math.max(0, block.length - 4)));
        } else {
            crc.update(block, 0, Math.max(0, block.length - 4));
        }
        return crc.getValue() & 0xFFFF_FFFFL;
    }

    public static long stored(byte[] block) {
        int n = block.length;
        if (n < 4) return -1;
        return ((block[n - 4] & 0xFFL) << 24)
                | ((block[n - 3] & 0xFFL) << 16)
                | ((block[n - 2] & 0xFFL) << 8)
                | (block[n - 1] & 0xFFL);
    }

    public static boolean verify(int version, byte[] block) {
        return stored(block) == compute(version, block);
    }
}
