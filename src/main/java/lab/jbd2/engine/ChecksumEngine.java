package lab.jbd2.engine;

import lab.jbd2.model.*;

import java.util.List;

/**
 * commit checksum（离线模型，对齐 jbd2 语义）：
 * 覆盖事务在环上的 descriptor、data、revoke 块字节（不含 commit 块自身），
 * 以 sequence 为种子；期望值记录在 commit 块偏移 20（u32，位于 commit_header 之后）。
 */
public final class ChecksumEngine {

    private ChecksumEngine() {}

    public static ChecksumVerdict verify(JournalSuperblock sb, List<JournalBlock> ring,
                                         long first, long maxlen, long seq,
                                         long descriptorPhys, long commitPhys,
                                         List<Diagnostic> sink) {
        int algo = (int) sb.checksumType.value;
        long acc = seq;
        long dist = ((commitPhys - descriptorPhys) % maxlen + maxlen) % maxlen;
        for (long k = 0; k < dist; k++) {
            long phys = first + ((descriptorPhys - first + k) % maxlen);
            JournalBlock jb = findByPhys(ring, phys);
            if (jb == null) {
                sink.add(Diagnostic.error("checksum-gap", "journal-block:" + phys,
                        "checksum 覆盖区间内缺少物理块 " + phys));
                return ChecksumVerdict.fail(-1, -1, algo, Judgment.R_CHECKSUM_BOUNDARY,
                        "覆盖区间存在缺块");
            }
            acc = Checksums.compute(algo, acc, jb.bytes, 0, jb.bytes.length);
        }
        JournalBlock commit = findByPhys(ring, commitPhys);
        if (commit == null || commit.bytes.length < 24) {
            return ChecksumVerdict.fail(-1, acc, algo, Judgment.R_CHECKSUM_BOUNDARY,
                    "commit 块过短，无法读取 checksum");
        }
        long expected = Hex.u32l(commit.bytes, 20);
        if (expected == acc) {
            return ChecksumVerdict.pass(acc, algo, Judgment.R_CHECKSUM_BOUNDARY);
        }
        return ChecksumVerdict.fail(expected, acc, algo, Judgment.R_CHECKSUM_BOUNDARY,
                "seq=" + Seq.hex(seq) + " commit checksum 不匹配：记录="
                        + Long.toHexString(expected) + " 重算=" + Long.toHexString(acc)
                        + "（算法=" + Checksums.typeName(algo) + "）");
    }

    /** 构造期：给定事务覆盖块（不含 commit），计算应写入 commit 块偏移 20 的值。 */
    public static long computeFor(JournalSuperblock sb, List<byte[]> coveredBlocks) {
        int algo = (int) sb.checksumType.value;
        long acc = -1; // 构造时由 builder 用 sequence 重新播种
        return acc;
    }

    public static long computeFor(JournalSuperblock sb, long seq, List<byte[]> coveredBlocks) {
        int algo = (int) sb.checksumType.value;
        long acc = seq;
        for (byte[] b : coveredBlocks) {
            acc = Checksums.compute(algo, acc, b, 0, b.length);
        }
        return acc;
    }

    private static JournalBlock findByPhys(List<JournalBlock> ring, long phys) {
        for (JournalBlock jb : ring) if (jb.phys == phys) return jb;
        return null;
    }
}
