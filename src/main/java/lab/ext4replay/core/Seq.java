package lab.ext4replay.core;

/**
 * JBD2 事务序号是 32 位无符号回绕计数器。
 * 环形日志里序号大小必须用「有符号差」比较（与内核 JBD2 一致），
 * 绝不能用普通整数排序，否则回绕点附近顺序会颠倒。
 */
public final class Seq {
    private Seq() {}

    /** 负数表示 a 在 b 之前（回绕安全，基于 32 位有符号差）。 */
    public static int compare(long a, long b) {
        return Integer.signum((int) (a - b));
    }

    public static boolean before(long a, long b) {
        return (int) (a - b) < 0;
    }

    public static boolean after(long a, long b) {
        return (int) (a - b) > 0;
    }

    /** a 是否在 [lo, hi] 的回绕安全区间内（含端点）。 */
    public static boolean inRange(long a, long lo, long hi) {
        return !before(a, lo) && !after(a, hi);
    }

    /** 回绕安全的 max。 */
    public static long max(long a, long b) {
        return before(a, b) ? b : a;
    }

    public static String fmt(long seq) {
        return Long.toString(seq & 0xFFFFFFFFL);
    }
}
