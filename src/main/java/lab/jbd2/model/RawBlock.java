package lab.jbd2.model;

/** 原始物理块：块号是相对日志设备起点的坐标，unit=block。字节原样保留。 */
public record RawBlock(long index, String unit, String coordinate, byte[] bytes) {
    public int len() { return bytes.length; }
}
