package gsb.jbd2;

/**
 * 32 位日志序列号的回绕感知比较（串行号算术，RFC 1982 风格）。
 * 环形日志序号回绕时禁止普通整数排序，一律走这里的模 2^32 比较。
 */
public final class SeqNum {
    public static final long MOD = 1L << 32;
    public static final long HALF = 1L << 31;

    private SeqNum() {}

    public static long normalize(long seq) {
        return seq & 0xFFFF_FFFFL;
    }

    /** 正数表示 a 比 b 新，负数表示 a 更旧，0 表示相等。 */
    public static int compare(long a, long b) {
        long d = (normalize(a) - normalize(b)) & 0xFFFF_FFFFL;
        if (d == 0) return 0;
        return d < HALF ? 1 : -1;
    }

    public static boolean before(long a, long b) {
        return compare(a, b) < 0;
    }

    /** a 是否落在「不超过 b」的过去半环内（revoke 的 sequence 范围判定用）。 */
    public static boolean beforeOrEqual(long a, long b) {
        return compare(a, b) <= 0;
    }

    public static String hex(long seq) {
        return String.format("0x%08X", normalize(seq));
    }
}
