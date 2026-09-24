package lab.jbd2.model;

/** JBD2 32 位回绕序号：禁止用普通整数排序。 */
public final class Seq {
    public static final long MOD = 1L << 32;
    public static final long MAX = 0xffffffffL;

    private Seq() {}

    public static long next(long seq) {
        return (seq + 1) & MAX;
    }

    /**
     * 环形比较：以 b 为窗口起点（窗口长度 2^31），
     * 返回 a 相对 b 的顺序：负数=更早，0=相同，正数=更晚。
     * 例：compare(0x00000000, 0xfffffffe) > 0 —— 0 在 fffffffe 之后。
     */
    public static int compare(long a, long b) {
        long d = (a - b) & MAX;
        if (d == 0) return 0;
        return d < (1L << 31) ? 1 : -1;
    }

    /** a 是否在环形顺序上早于 b。 */
    public static boolean before(long a, long b) {
        return compare(a, b) < 0;
    }

    /** [start, end] 的环形距离（端到端步数，含两端）。 */
    public static long spanClosed(long start, long end) {
        return ((end - start) & MAX) + 1;
    }

    public static String hex(long seq) {
        return String.format("%08x", seq);
    }
}
