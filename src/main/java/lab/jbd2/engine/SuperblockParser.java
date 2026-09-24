package lab.jbd2.engine;

import lab.jbd2.model.Hex;
import lab.jbd2.model.JournalSuperblock;
import lab.jbd2.model.Raw;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 JBD2 日志 superblock。字段偏移按 Linux jbd2/jbd2.h：
 *   s_blocktype 0, s_seq 4, s_errno 8, s_nr_users 12, s_dynamic_first 16,
 *   s_maxlen 20, s_dynamic_max 24, s_dynamic_super 28, s_log_rev_number 32,
 *   s_start 40 (>=2), s_first_commit_time 44 ... s_first_commit_id 68 ...
 * checksum 类型在 v2 扩展区偏移 256（s_checksum_type, jbd2 sb 宽布局）。
 */
public final class SuperblockParser {

    private SuperblockParser() {}

    public static JournalSuperblock parse(byte[] b) {
        List<String> diags = new ArrayList<>();
        boolean magicOk = b.length >= 4 && Hex.u32(b, 0) == JournalSuperblock.MAGIC;
        if (!magicOk) diags.add("journal-sb: magic 不匹配 (offset 0)");
        if (b.length < 260) diags.add("journal-sb: 字节不足 260，checksum 类型字段缺失");

        Raw<Long> errno = field(b, 8, "s_errno", "u32", "journal-sb+8", v -> v, diags);
        Raw<Long> first = field(b, 16, "s_dynamic_first", "blocks", "journal-sb+16",
                v -> v >= 1 ? v : null, diags);
        Raw<Long> maxlen = field(b, 20, "s_maxlen", "blocks", "journal-sb+20",
                v -> v >= 1 ? v : null, diags);
        Raw<Long> revision = field(b, 32, "s_log_rev_number", "u32", "journal-sb+32",
                v -> v, diags);
        Raw<Long> start = field(b, 40, "s_start", "blocks", "journal-sb+40",
                v -> v >= 1 ? v : null, diags);
        Raw<Long> firstCommit = field(b, 68, "s_first_commit_id", "sequence", "journal-sb+68",
                v -> v, diags);
        long csType = b.length >= 260 ? Hex.u32l(b, 256) : 0L;
        Raw<Long> checksumType = b.length >= 260
                ? Raw.ok(String.valueOf(csType), "enum(crc32=0,crc32c=1)", "journal-sb+256",
                         csType > 1 ? 0L : csType)
                : Raw.bad("(absent)", "enum(crc32=0,crc32c=1)", "journal-sb+256",
                          "字段缺失，无法确定 checksum 版本");
        if (b.length >= 260 && csType > 1) {
            diags.add("journal-sb+256: 未知 checksum 类型 " + csType);
        }
        if (revision.valid() && revision.value == 0) {
            diags.add("journal-sb: s_log_rev_number=0（v1 日志，不支持 v2/v3 特性）");
        }
        return new JournalSuperblock(b, first, start, maxlen, firstCommit, checksumType,
                errno, magicOk, diags);
    }

    private interface Check { Long apply(long v); }

    private static Raw<Long> field(byte[] b, int off, String name, String unit,
                                   String coord, Check check, List<String> diags) {
        if (b.length < off + 4) {
            String diag = name + ": 字段超出块边界 (" + coord + ")";
            diags.add(diag);
            return Raw.bad("(absent)", unit, coord, diag);
        }
        long v = Hex.u32l(b, off);
        Long checked = check.apply(v);
        if (checked == null) {
            String diag = name + "=" + v + " 不合法（" + coord + "）";
            diags.add(diag);
            return Raw.bad(String.valueOf(v), unit, coord, diag);
        }
        return Raw.ok(String.valueOf(v), unit, coord, checked);
    }
}
