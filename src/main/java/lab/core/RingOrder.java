package lab.core;

/**
 * 环形日志序号比较。sequence 是 32 位无符号值并会回绕；
 * 不能用普通整数排序——在距离半环以内按回绕语义比较。
 */
public final class RingOrder {
    private RingOrder() {}

    /** a 与 b 的回绕距离，落在 (-2^31, 2^31]；负 => a 早于 b。 */
    public static int distance(long a, long b) {
        return (int) ((a - b) & 0xffffffffL);
    }

    public static boolean before(long a, long b) {
        return distance(a, b) < 0;
    }

    public static boolean after(long a, long b) {
        return distance(a, b) > 0;
    }

    /** a 是否位于 (start, start+half] 的半环窗口内（回绕感知）。 */
    public static boolean withinWindow(long a, long start) {
        int d = distance(a, start);
        return d > 0 && d <= (1 << 30);
    }
}
