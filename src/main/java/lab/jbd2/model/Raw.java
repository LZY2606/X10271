package lab.jbd2.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 原始表示：导入时保留原样，不做隐式转换。
 * value 为规范化后的派生值；无法转换时为 null，诊断写入 diagnostics。
 * 每个原始值都携带 unit（单位）与 coordinate（坐标基准）。
 */
public final class Raw<T> {
    public final String raw;
    public final String unit;
    public final String coordinate;
    public final T value;
    public final List<String> diagnostics;

    private Raw(String raw, String unit, String coordinate, T value, List<String> diagnostics) {
        this.raw = raw;
        this.unit = unit;
        this.coordinate = coordinate;
        this.value = value;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static <T> Raw<T> ok(String raw, String unit, String coordinate, T value) {
        return new Raw<>(raw, unit, coordinate, value, List.of());
    }

    public static <T> Raw<T> bad(String raw, String unit, String coordinate, String diag) {
        return new Raw<>(raw, unit, coordinate, null, List.of(diag));
    }

    public boolean valid() {
        return value != null && diagnostics.isEmpty();
    }

    @Override public String toString() {
        return "Raw[" + coordinate + "=" + raw + " " + unit
                + (valid() ? "" : " !" + diagnostics) + "]";
    }
}
