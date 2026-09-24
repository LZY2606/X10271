package org.jbd2lab.layout;

import org.jbd2lab.util.Be;

/** Parsed commit block. */
public record Commit(long magic, int blocktype, long sequence, long checksumOrZero, long tailCrc) {

    public static Commit parse(byte[] b, int checksumVersion) {
        return new Commit(
                Be.u32(b, 0),
                (int) Be.u32(b, 4),
                Be.u32(b, 8),
                Be.u32(b, 12),
                checksumVersion >= Jbd2.CSUM_VERSION_1 ? Be.u32(b, b.length - 4) : 0L);
    }
}
