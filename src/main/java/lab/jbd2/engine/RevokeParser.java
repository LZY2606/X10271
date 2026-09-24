package lab.jbd2.engine;

import lab.jbd2.model.Hex;

import java.util.ArrayList;
import java.util.List;

/**
 * revoke 块（jbd2 revoke table v2，对齐 rsv2_*）：
 *   公共头 12 字节 + count u32 + 每项 blocknr u32（big-endian）。
 */
public final class RevokeParser {
    private RevokeParser() {}

    public static List<Long> parse(byte[] block) {
        List<Long> out = new ArrayList<>();
        if (block.length < 16) return out;
        long count = Hex.u32l(block, 12);
        int off = 16;
        for (long i = 0; i < count && off + 4 <= block.length; i++) {
            out.add(Hex.u32l(block, off));
            off += 4;
        }
        return out;
    }
}
