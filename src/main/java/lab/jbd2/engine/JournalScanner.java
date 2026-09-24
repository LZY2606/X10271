package lab.jbd2.engine;

import lab.jbd2.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志环扫描：
 *  物理块按 s_dynamic_first..s_dynamic_first+s_maxlen-1 环形排列；
 *  s_start 是头指针（下一可用块），扫描从它开始（崩溃后最老记录的起点附近），
 *  实际起点由首次出现 descriptor 头校准。
 * 输出：环块视图（JournalBlock）+ 事务组（回绕序）+ orphan 尾部。
 */
public final class JournalScanner {

    public static final class Scan {
        public List<JournalBlock> ring = new ArrayList<>();
        public List<TransactionGroup> groups = new ArrayList<>();
        public List<Diagnostic> diagnostics = new ArrayList<>();
    }

    private JournalScanner() {}

    /**
     * @param blocks 物理坐标 -> 字节（必须含 [first, first+maxlen)）
     * @param truncatedFrom 崩溃截断：物理块号 >= 此值（环顺序）不存在
     */
    public static Scan scan(JournalSuperblock sb, long blockSize,
                            java.util.Map<Long, byte[]> blocks,
                            Long truncatedFrom, List<Diagnostic> sink) {
        Scan out = new Scan();
        long first = sb.first.value;
        long maxlen = sb.maxlen.value;
        long start = sb.start.value;

        // 1) 环物理顺序（含缺失/零块检测）
        List<Long> physOrder = ringOrder(first, maxlen);
        List<JournalBlock> ring = new ArrayList<>();
        for (long phys : physOrder) {
            byte[] b = blocks.get(phys);
            if (b == null) {
                if (truncatedFrom != null && ringDistance(first, maxlen, truncatedFrom, phys) >= 0
                        && ringDistance(first, maxlen, phys, truncatedFrom) > 0) {
                    out.diagnostics.add(Diagnostic.warn("truncated",
                            "journal-block:" + phys,
                            "崩溃点截断：物理块 " + phys + " 未落盘"));
                }
                ring.add(new JournalBlock(phys, JournalBlock.Kind.FREE, null,
                        List.of(), List.of(), new byte[(int) blockSize]));
                continue;
            }
            ring.add(classify(phys, b, sb.checksumType.value));
        }
        out.ring = ring;

        // 2) 从头指针附近校准：从 s_start 起找第一个 descriptor
        int startIdx = (int) ((start - first) % maxlen);
        int descIdx = -1;
        for (int step = 0; step < maxlen; step++) {
            int i = (startIdx + step) % (int) maxlen;
            JournalBlock jb = ring.get(i);
            if (jb.kind == JournalBlock.Kind.DESCRIPTOR) { descIdx = i; break; }
        }
        if (descIdx == -1) {
            out.diagnostics.add(Diagnostic.error("empty-ring", "ring",
                    "从 s_start=" + start + " 起未找到任何 descriptor 块"));
            return out;
        }

        // 3) 顺序组装事务组，直到回到起点或遇到 FREE（截断）
        List<Integer> walk = new ArrayList<>();
        for (int step = 0; step < maxlen; step++) walk.add((descIdx + step) % (int) maxlen);

        int i = 0;
        while (i < walk.size()) {
            int idx = walk.get(i);
            JournalBlock cur = ring.get(idx);
            if (cur.kind == JournalBlock.Kind.DESCRIPTOR) {
                long seq = cur.header.sequence();
                int beginIdx = idx;
                List<Long> covered = new ArrayList<>();
                List<DescriptorTag> tags = new ArrayList<>(cur.tags);
                List<JournalBlock> revokes = new ArrayList<>();
                covered.add(cur.phys);

                // descriptor 标签的数据块（按标签顺序消费后续 DATA 块；
                // deleted 标签不消费数据块，ESCAPE 后的 magic 块仍是数据块）
                List<DescriptorTag> withData = new ArrayList<>();
                int nextTag = 0;
                int j = i + 1;
                boolean truncated = false;
                Long commitPhys = null;
                while (j < walk.size()) {
                    JournalBlock nxt = ring.get(walk.get(j));
                    if (nxt.kind == JournalBlock.Kind.FREE) { truncated = true; break; }
                    covered.add(nxt.phys);
                    if (nxt.kind == JournalBlock.Kind.REVOKE) {
                        revokes.add(nxt);
                        j++;
                        continue;
                    }
                    if (nxt.kind == JournalBlock.Kind.COMMIT
                            && Seq.compare(nxt.header.sequence(), seq) == 0) {
                        commitPhys = nxt.phys;
                        j++;
                        break;
                    }
                    if (nxt.kind == JournalBlock.Kind.DESCRIPTOR) {
                        // 没有 commit 就出现下一个 descriptor：半事务
                        truncated = true;
                        break;
                    }
                    // 跳过 deleted 标签（无数据块）
                    while (nextTag < cur.tags.size() && !cur.tags.get(nextTag).mapped()) {
                        withData.add(cur.tags.get(nextTag));
                        nextTag++;
                    }
                    if (nextTag < cur.tags.size()) {
                        DescriptorTag t = cur.tags.get(nextTag);
                        withData.add(new DescriptorTag(t.ordinal(), t.blocknr(), t.mapped(),
                                t.lastTag(), t.escape(), t.flags(), t.tagOffset(), nxt.phys));
                        nextTag++;
                    } else {
                        out.diagnostics.add(Diagnostic.warn("orphan-data",
                                "journal-block:" + nxt.phys,
                                "数据块数量超过 descriptor 标签数（seq=" + Seq.hex(seq) + "）"));
                    }
                    j++;
                }
                while (nextTag < cur.tags.size()) {
                    withData.add(cur.tags.get(nextTag));
                    nextTag++;
                }
                if (j >= walk.size() && commitPhys == null) truncated = true;

                // 用带数据坐标的标签替换
                tags.clear();
                tags.addAll(withData);

                boolean wraps = ringDistance(first, maxlen, cur.phys,
                        covered.get(covered.size() - 1)) < 0;
                long lastPhys = covered.get(covered.size() - 1);

                TransactionGroup.Status status = commitPhys != null
                        ? TransactionGroup.Status.COMMITTED
                        : TransactionGroup.Status.HALF;
                ChecksumVerdict cv = commitPhys != null
                        ? ChecksumEngine.verify(sb, ring, first, maxlen, seq,
                                cur.phys, commitPhys, sink)
                        : new ChecksumVerdict(false, -1, -1, (int) sb.checksumType.value,
                                Judgment.R_CHECKSUM_BOUNDARY, "无 commit 块，无法校验");

                TransactionGroup g = new TransactionGroup(seq, status, cur.phys,
                        covered, tags, revokes, commitPhys, cur.phys, lastPhys, wraps, cv);
                out.groups.add(g);
                i = j;
                if (truncated) {
                    // 尾部剩余块归为 ORPHAN_TAIL 的不确定边界
                    while (i < walk.size()) {
                        JournalBlock tail = ring.get(walk.get(i));
                        if (tail.kind != JournalBlock.Kind.FREE
                                && tail.kind != JournalBlock.Kind.ZERO) {
                            out.diagnostics.add(Diagnostic.warn("orphan-tail",
                                    "journal-block:" + tail.phys,
                                    "commit 缺失后的尾部块，边界不确定"));
                        }
                        i++;
                    }
                    break;
                }
            } else {
                i++;
            }
        }
        return out;
    }

    /** 环物理顺序：first, first+1, ... first+maxlen-1（不做序号回绕，这是物理坐标）。 */
    static List<Long> ringOrder(long first, long maxlen) {
        List<Long> l = new ArrayList<>();
        for (long k = 0; k < maxlen; k++) l.add(first + k);
        return l;
    }

    /** 物理块坐标沿环前进的有向距离；a 恰在 b 后一圈时返回负数（不可达，用于 wraps 判断）。 */
    static long ringDistance(long first, long maxlen, long a, long b) {
        long da = (a - first) % maxlen;
        long db = (b - first) % maxlen;
        return db - da;
    }

    static JournalBlock classify(long phys, byte[] b, long checksumType) {
        BlockHeader h = BlockHeader.read(b);
        if (h == null) {
            boolean allZero = true;
            for (byte x : b) if (x != 0) { allZero = false; break; }
            return new JournalBlock(phys, allZero ? JournalBlock.Kind.ZERO : JournalBlock.Kind.DATA,
                    null, List.of(), List.of(), b);
        }
        return switch (h.type()) {
            case BlockHeader.DESC -> new JournalBlock(phys, JournalBlock.Kind.DESCRIPTOR, h,
                    DescriptorParser.parse(b, checksumType), List.of(), b);
            case BlockHeader.COMMIT -> new JournalBlock(phys, JournalBlock.Kind.COMMIT, h,
                    List.of(), List.of(), b);
            case BlockHeader.REVOKE -> new JournalBlock(phys, JournalBlock.Kind.REVOKE, h,
                    List.of(), RevokeParser.parse(b), b);
            default -> new JournalBlock(phys, JournalBlock.Kind.DATA, h,
                    List.of(), List.of(), b);
        };
    }
}
