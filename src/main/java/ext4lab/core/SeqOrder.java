package ext4lab.core;

/**
 * 环形日志序号比较：JBD2 的 sequence 是 32 位环绕计数器，
 * 禁止用普通整数排序。采用模 2^32 的半圆比较：
 * 若 (a - b) mod 2^32 落在 (0, 2^31) 内，则 a 比 b 新。
 */
public final class SeqOrder {
    private SeqOrder() {}

    public static final long MOD = 1L << 32;
    public static final long HALF = 1L << 31;

    /** @return 正数表示 a 更新，负数表示 a 更旧，0 表示相等。 */
    public static int compare(long a, long b) {
        long diff = Math.floorMod(a - b, MOD);
        if (diff == 0) return 0;
        return diff < HALF ? 1 : -1;
    }

    /** a 是否不旧于 b（a >= b，环形意义下）。 */
    public static boolean newerOrEqual(long a, long b) {
        return compare(a, b) >= 0;
    }

    /** 环形意义下的较大者。 */
    public static long max(long a, long b) {
        return compare(a, b) >= 0 ? a : b;
    }
}
