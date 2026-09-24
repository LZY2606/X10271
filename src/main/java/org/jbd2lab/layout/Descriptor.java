package org.jbd2lab.layout;

import org.jbd2lab.util.Be;

import java.util.ArrayList;
import java.util.List;

/** Parsed descriptor block header plus its tag stream. */
public record Descriptor(long magic, int blocktype, long sequence, List<Tag> tags, long blockCrc) {

    public static Descriptor parse(byte[] b, int checksumVersion) {
        long magic = Be.u32(b, 0);
        int type = (int) Be.u32(b, 4);
        long seq = Be.u32(b, 8);
        List<Tag> tags = new ArrayList<>();
        int off = Jbd2.HEADER_SIZE;
        int tagSize = checksumVersion >= Jbd2.CSUM_VERSION_2 ? Jbd2.TAG_SIZE_CSUM : Jbd2.TAG_SIZE_BASE;
        while (off + tagSize <= b.length) {
            long blocknrLow = Be.u32(b, off);
            int flags = Be.u16(b, off + 8);
            int blocknrHigh = (int) Be.u32(b, off + 10);
            long blocknr = blocknrLow | ((long) blocknrHigh << 32);
            long tagCrc = checksumVersion >= Jbd2.CSUM_VERSION_2 ? Be.u32(b, off + 14) : 0L;
            if (blocknrLow == 0 && blocknrHigh == 0 && flags == 0) {
                break;
            }
            tags.add(new Tag(blocknr, flags, -1,
                    (flags & Jbd2.TAG_FLAG_DELETED) != 0,
                    (flags & Jbd2.TAG_FLAG_ESCAPE) != 0,
                    (flags & Jbd2.TAG_FLAG_LAST_TAG) != 0,
                    tagCrc));
            off += tagSize;
            if ((flags & Jbd2.TAG_FLAG_LAST_TAG) != 0) {
                break;
            }
        }
        long blockCrc = checksumVersion == Jbd2.CSUM_VERSION_1
                ? Be.u32(b, b.length - 4) : 0L;
        return new Descriptor(magic, type, seq, List.copyOf(tags), blockCrc);
    }
}
