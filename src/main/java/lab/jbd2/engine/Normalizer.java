package lab.jbd2.engine;

import lab.jbd2.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 派生层规范化：从案例包的原始参数派生计算用数值。
 * 任何无法转换的值留在原位置并附诊断 —— 绝不为了画图而丢弃。
 */
public final class Normalizer {

    private Normalizer() {}

    public static final class Result {
        public final ReplayPlan.NormalizedParams params;
        public final List<Diagnostic> diagnostics;
        public Result(ReplayPlan.NormalizedParams p, List<Diagnostic> d) {
            this.params = p; this.diagnostics = List.copyOf(d);
        }
    }

    public static Result normalize(CaseBundle c, JournalSuperblock sb) {
        List<Diagnostic> diags = new ArrayList<>();
        ReplayPlan.NormalizedParams p = new ReplayPlan.NormalizedParams();

        // block size：接受 "1024"/"1024B"/"1 KiB" 等；非法值原样保留
        CaseBundle.RawParam bs = c.params.get("blockSize");
        long blockSize = 1024;
        boolean bsValid = false;
        String bsRaw = bs == null ? null : bs.raw;
        if (bs != null) {
            Long parsed = parseBlockSize(bs.raw);
            if (parsed != null && parsed >= 512 && parsed <= 65536
                    && (parsed & (parsed - 1)) == 0) {
                blockSize = parsed;
                bsValid = true;
            } else {
                diags.add(Diagnostic.error(DiagnosticBlocks.BLOCKSIZE,
                        bs.coordinate == null ? "params.blockSize" : bs.coordinate,
                        "无效块大小，原始表示保留：\"" + bs.raw + "\" " + bs.unit
                                + "；合法范围 512..65536 的 2 的幂（bytes）"));
            }
        } else {
            diags.add(Diagnostic.warn(DiagnosticBlocks.BLOCKSIZE, "params.blockSize",
                    "缺少 blockSize 参数"));
        }
        p.blockSize = blockSize;
        p.blockSizeRaw = bsRaw;
        p.blockSizeValid = bsValid;
        p.blockSizeUnit = bs == null ? "bytes" : bs.unit;

        if (sb.first.valid() && sb.start.valid() && sb.maxlen.valid()) {
            p.ringFirst = sb.first.value;
            p.ringStart = sb.start.value;
            p.ringMaxlen = sb.maxlen.value;
        }
        p.checksumType = sb.checksumType.valid() ? sb.checksumType.value : 0;

        if (c.crashPoint != null && c.crashPoint.raw != null && !c.crashPoint.raw.isBlank()) {
            Long cp = parseSeq(c.crashPoint.raw);
            if (cp != null) {
                p.crashSeq = cp;
            } else {
                diags.add(Diagnostic.warn(DiagnosticBlocks.CRASH_SLICE,
                        c.crashPoint.coordinate == null ? "params.crashPoint" : c.crashPoint.coordinate,
                        "崩溃点原始表示无法解析：\"" + c.crashPoint.raw + "\"，按无崩溃点处理"));
                p.crashSeq = -1;
            }
        } else {
            p.crashSeq = -1;
        }
        return new Result(p, diags);
    }

    static Long parseBlockSize(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase()
                .replace("bytes", "").replace("byte", "")
                .replace("b", "").trim();
        try {
            if (t.endsWith("k") || t.endsWith("kib") || t.endsWith("kb")) {
                return Long.parseLong(t.replaceAll("[^0-9]", "")) * 1024;
            }
            return Long.parseLong(t.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long parseSeq(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase();
        try {
            if (t.startsWith("0x")) return Long.parseLong(t.substring(2), 16) & Seq.MAX;
            return Long.parseLong(t) & Seq.MAX;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static final class DiagnosticBlocks {
        public static final String BLOCKSIZE = Judgment.R_BLOCKSIZE;
        public static final String CRASH_SLICE = Judgment.R_CRASH_SLICE;
    }

    static Map<String, String> ev(String... kv) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Map.copyOf(m);
    }
}
