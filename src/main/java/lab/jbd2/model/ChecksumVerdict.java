package lab.jbd2.model;

/** checksum 裁定。expected/actual 为提交块中记录值与重算值（0..2^32-1）。 */
public record ChecksumVerdict(boolean ok, long expected, long actual, int algo,
                              String rule, String detail) {
    public static ChecksumVerdict pass(long v, int algo, String rule) {
        return new ChecksumVerdict(true, v, v, algo, rule, "commit checksum 匹配");
    }
    public static ChecksumVerdict fail(long expected, long actual, int algo, String rule, String detail) {
        return new ChecksumVerdict(false, expected, actual, algo, rule, detail);
    }
}
