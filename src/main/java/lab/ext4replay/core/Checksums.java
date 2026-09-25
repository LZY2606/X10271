package lab.ext4replay.core;

import java.util.zip.CRC32C;

/**
 * 实验室 checksum 方案（对应 ext4 v3 元数据 crc32c）：
 * 对整块字节计算 CRC32C，计算前把 [16,20) 四个字节清零，
 * 结果大端写回 offset 16。v2（checksumType=0）不校验。
 */
public final class Checksums {
    private Checksums() {}

    public static int blockCrc32c(byte[] block) {
        byte[] copy = block.clone();
        copy[16] = copy[17] = copy[18] = copy[19] = 0;
        CRC32C crc = new CRC32C();
        crc.update(copy, 0, copy.length);
        return (int) crc.getValue();
    }

    public static void stamp(byte[] block) {
        int crc = blockCrc32c(block);
        block[16] = (byte) (crc >>> 24);
        block[17] = (byte) (crc >>> 16);
        block[18] = (byte) (crc >>> 8);
        block[19] = (byte) crc;
    }

    public static boolean verify(byte[] block) {
        int stored = ((block[16] & 0xFF) << 24) | ((block[17] & 0xFF) << 16)
                | ((block[18] & 0xFF) << 8) | (block[19] & 0xFF);
        return stored == blockCrc32c(block);
    }
}
