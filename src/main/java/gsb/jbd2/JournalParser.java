package gsb.jbd2;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 日志环扫描器。块内布局（教学用简化 JBD2 格式）：
 * <pre>
 *   offset 0..3   magic 0xC03B3998（大端）
 *   offset 4..7   块类型：1=descriptor 2=commit 5=revoke（data 块无 magic，按位置识别）
 *   offset 8..11  sequence（32 位无符号，回绕语义）
 *   descriptor:   offset 12 起 16 字节标签：u32 fs块号, u16 flags(bit0=LAST), 10 字节保留
 *   revoke:       offset 12 u32 计数，随后 u32 fs 块号列表
 *   末 4 字节     checksum（见 Checksums）
 * </pre>
 * 扫描顺序一律为环顺序（自 startOffset 起），不按 sequence 整数排序。
 */
public final class JournalParser {
    public static final long MAGIC = 0xC03B3998L;
    public static final int T_DESCRIPTOR = 1;
    public static final int T_COMMIT = 2;
    public static final int T_REVOKE = 5;

    private JournalParser() {}

    public record ScanResult(Model.Journal journal, List<String> diagnostics) {}

    public static ScanResult scan(Model.Superblock sb, List<String> rawBlocks, long startOffsetBlocks) {
        List<Model.JournalBlock> out = new ArrayList<>();
        List<String> diags = new ArrayList<>();

        if (sb.blockSizeBytes() == null) {
            // 无法转换的值留在原位置并附诊断，不为画图丢弃
            String diag = "INVALID_BLOCK_SIZE: " + sb.blockSizeRaw() + " " + sb.blockSizeUnit()
                    + "（" + sb.blockSizeDiagnostic() + "）";
            diags.add(diag);
            for (int i = 0; i < rawBlocks.size(); i++) {
                out.add(new Model.JournalBlock(i, Model.BlockTag.UNKNOWN, 0,
                        List.of(), List.of(), false, "n/a", rawBlocks.get(i), diag));
            }
            return new ScanResult(new Model.Journal(startOffsetBlocks, out), diags);
        }

        int bs = sb.blockSizeBytes().intValue();
        int expectData = 0;
        long openSeq = 0;
        for (int i = 0; i < rawBlocks.size(); i++) {
            String hex = rawBlocks.get(i);
            byte[] b;
            try {
                b = HexFormat.of().parseHex(hex);
            } catch (IllegalArgumentException e) {
                diags.add("blk" + i + ": 非法十六进制，原样保留");
                out.add(new Model.JournalBlock(i, Model.BlockTag.UNKNOWN, 0,
                        List.of(), List.of(), false, "n/a", hex, "非法十六进制"));
                continue;
            }
            if (b.length != bs) {
                diags.add("blk" + i + ": 长度 " + b.length + " 字节 != 块大小 " + bs + " 字节，原样保留");
            }
            if (expectData > 0) {
                // data 块无 magic，按位置归属当前 descriptor
                out.add(new Model.JournalBlock(i, Model.BlockTag.DATA, openSeq,
                        List.of(), List.of(), true, "data 块无 checksum", hex, null));
                expectData--;
                continue;
            }
            if (isAllZero(b)) {
                out.add(new Model.JournalBlock(i, Model.BlockTag.UNKNOWN, 0,
                        List.of(), List.of(), true, "n/a", hex, "空槽位（全零）"));
                continue;
            }
            long magic = u32(b, 0);
            if (magic != MAGIC) {
                diags.add("blk" + i + ": 无 JBD2 magic 且无打开的 descriptor");
                out.add(new Model.JournalBlock(i, Model.BlockTag.UNKNOWN, 0,
                        List.of(), List.of(), false, "n/a", hex, "无 JBD2 magic"));
                continue;
            }
            int type = (int) u32(b, 4);
            long seq = u32(b, 8);
            boolean crcOk = Checksums.verify(sb.checksumVersion(), b);
            String crcDetail = "v" + sb.checksumVersion() + " stored=" + Long.toHexString(Checksums.stored(b))
                    + " computed=" + Long.toHexString(Checksums.compute(sb.checksumVersion(), b));
            switch (type) {
                case T_DESCRIPTOR -> {
                    List<Long> targets = new ArrayList<>();
                    for (int off = 12; off + 16 <= b.length - 4; off += 16) {
                        long fs = u32(b, off);
                        int flags = u16(b, off + 4);
                        targets.add(fs);
                        if ((flags & 0x1) != 0) break;
                    }
                    expectData = targets.size();
                    openSeq = seq;
                    out.add(new Model.JournalBlock(i, Model.BlockTag.DESCRIPTOR, seq,
                            targets, List.of(), crcOk, crcDetail, hex, crcOk ? null : "descriptor checksum 失败"));
                }
                case T_COMMIT -> out.add(new Model.JournalBlock(i, Model.BlockTag.COMMIT, seq,
                        List.of(), List.of(), crcOk, crcDetail, hex, crcOk ? null : "commit checksum 失败"));
                case T_REVOKE -> {
                    List<Long> revoked = new ArrayList<>();
                    long count = u32(b, 12);
                    for (int k = 0; k < count && 16 + 4L * k + 4 <= b.length; k++) {
                        revoked.add(u32(b, 16 + 4 * k));
                    }
                    out.add(new Model.JournalBlock(i, Model.BlockTag.REVOKE, seq,
                            List.of(), revoked, crcOk, crcDetail, hex, crcOk ? null : "revoke checksum 失败"));
                }
                default -> {
                    diags.add("blk" + i + ": 未知块类型 " + type);
                    out.add(new Model.JournalBlock(i, Model.BlockTag.UNKNOWN, seq,
                            List.of(), List.of(), crcOk, crcDetail, hex, "未知块类型 " + type));
                }
            }
        }
        return new ScanResult(new Model.Journal(startOffsetBlocks, out), diags);
    }

    static boolean isAllZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    static long u32(byte[] b, int off) {
        if (off + 4 > b.length) return 0;
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    static int u16(byte[] b, int off) {
        if (off + 2 > b.length) return 0;
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}
