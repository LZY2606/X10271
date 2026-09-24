package org.jbd2lab.layout;

import org.jbd2lab.util.Be;

import java.util.ArrayList;
import java.util.List;

/** Parsed revoke block. The lab always uses 64-bit big-endian block numbers. */
public record Revoke(long magic, int blocktype, long sequence, int count, List<Long> blocks,
                     long tailCrc) {

    public static Revoke parse(byte[] b, int checksumVersion) {
        long magic = Be.u32(b, 0);
        int type = (int) Be.u32(b, 4);
        long seq = Be.u32(b, 8);
        int count = (int) Be.u32(b, 12);
        List<Long> blocks = new ArrayList<>();
        int off = 16;
        for (int i = 0; i < count && off + 8 <= b.length - (checksumVersion >= 1 ? 4 : 0); i++) {
            blocks.add(Be.u64(b, off));
            off += 8;
        }
        long tail = checksumVersion >= Jbd2.CSUM_VERSION_1 ? Be.u32(b, b.length - 4) : 0L;
        return new Revoke(magic, type, seq, count, List.copyOf(blocks), tail);
    }
}
