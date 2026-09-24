package lab.jbd2.engine;

import lab.jbd2.model.DescriptorTag;
import lab.jbd2.model.Hex;

import java.util.ArrayList;
import java.util.List;

/**
 * descriptor 块标签解析（JBD2 tag2/tag3 简化模型）：
 *   tag2: blocknr u32, flags u16, blocknr_high u16            = 8 字节
 *   tag3: blocknr u32, flags u16, blocknr_high u16, csum u16 = 10 字节
 * flags: 1=LAST_TAG 2=FLUSHED/ESCAPE 4=deleted(未映射)。
 * ESCAPE 标签后的数据块即使以 magic 开头也按数据处理（不把它误读成元数据块）。
 */
public final class DescriptorParser {

    private DescriptorParser() {}

    public static List<DescriptorTag> parse(byte[] block, int checksumType) {
        List<DescriptorTag> tags = new ArrayList<>();
        int tagLen = checksumType == Checksums.CRC32C ? 10 : 8;
        int off = 12; // 跳过公共头
        int ordinal = 0;
        while (off + 8 <= block.length) {
            long low = Hex.u32l(block, off);
            int flags = Hex.u16(block, off + 4);
            long high = Hex.u16(block, off + 6);
            // 全零间隔：descriptor 块尾部可能有未使用空间
            if (low == 0 && high == 0 && flags == 0) {
                off += tagLen;
                continue;
            }
            long blocknr = low | (high << 32);
            boolean mapped = (flags & DescriptorTag.FL_DELETED) == 0;
            boolean last = (flags & DescriptorTag.FL_LAST) != 0;
            boolean escape = (flags & DescriptorTag.FL_ESCAPE) != 0;
            tags.add(new DescriptorTag(ordinal, blocknr, mapped, last, escape,
                    flags, off, -1L));
            ordinal++;
            off += tagLen;
            if (last) break;
        }
        return tags;
    }
}
