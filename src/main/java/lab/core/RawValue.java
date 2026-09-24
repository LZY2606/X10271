package lab.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lab.core.JsonUtil.JN;

/**
 * 领域量的原始表示。导入层只保存 original/unit/basis，规范化只发生在派生层；
 * 无法转换时 normalized 为 null 并附 diagnostic，值仍留在原位置。
 */
public record RawValue(String original, String unit, String basis, Long normalized, String diagnostic) {

    public static RawValue of(String original, String unit, String basis, Long normalized, String diagnostic) {
        return new RawValue(original, unit, basis, normalized, diagnostic);
    }

    public static RawValue imported(String original, String unit, String basis) {
        return new RawValue(original, unit, basis, null, null);
    }

    public ObjectNode toJson() {
        ObjectNode n = JN.obj();
        n.put("original", original);
        n.put("unit", unit);
        n.put("basis", basis);
        if (normalized != null) n.put("normalized", normalized);
        if (diagnostic != null) n.put("diagnostic", diagnostic);
        return n;
    }

    public static RawValue fromJson(JsonNode n) {
        return new RawValue(
                n.path("original").asText(null),
                n.path("unit").asText(null),
                n.path("basis").asText(null),
                n.hasNonNull("normalized") ? n.get("normalized").asLong() : null,
                n.path("diagnostic").asText(null));
    }

    public boolean convertible() { return normalized != null && diagnostic == null; }

    /**
     * 派生层规范化。原始表示永不被覆盖；这里返回携带 normalized 的新对象。
     */
    public RawValue normalize() {
        if (normalized != null) return this;
        if (original == null) return diag("缺少原始值，无法规范化（unit=" + unit + ", basis=" + basis + "）");
        String s = original.trim().toLowerCase().replace(" ", "");
        try {
            if ("bytes".equals(unit)) {
                long mult = 1;
                String num = s;
                if (s.endsWith("kib") || s.endsWith("kb")) { mult = 1024L; num = s.substring(0, s.length() - 3); }
                else if (s.endsWith("mib")) { mult = 1024L * 1024L; num = s.substring(0, s.length() - 3); }
                long v = Long.parseLong(num) * mult;
                if (v != 1024 && v != 2048 && v != 4096) {
                    return of(original, unit, basis, null,
                            "无效块大小: " + original + " -> " + v + " bytes；ext4 仅允许 1024/2048/4096");
                }
                return of(original, unit, basis, v, null);
            }
            if ("fs-block".equals(unit) || "journal-block".equals(unit) || "ring-index".equals(unit)) {
                long v = Long.parseLong(s);
                if (v < 0) return diag("负坐标: " + original);
                return of(original, unit, basis, v, null);
            }
            if ("enum".equals(unit)) {
                if (!"crc32".equals(s) && !"crc32c".equals(s)) {
                    return diag("未知 checksum 版本: " + original);
                }
                return of(original, unit, basis, "crc32c".equals(s) ? 1L : 0L, null);
            }
            if ("bytes-hex".equals(unit)) {
                String hex = s.replace("0x", "");
                if (hex.length() % 2 != 0) return diag("十六进制长度为奇数: " + original);
                return of(original, unit, basis, (long) hex.length() / 2, null);
            }
            return diag("未知单位 " + unit + "，拒绝猜测转换");
        } catch (NumberFormatException e) {
            return diag("无法解析数值 '" + original + "'（unit=" + unit + "）");
        }
    }

    private RawValue diag(String msg) {
        return of(original, unit, basis, null, msg);
    }
}
