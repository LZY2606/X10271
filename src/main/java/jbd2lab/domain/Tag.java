package jbd2lab.domain;

/** One descriptor-tag entry: maps the following journal data block to a filesystem block. */
public record Tag(long fsBlock, int flags, long tagBlockCrc, int journalBlockAbs) {
    public boolean escape() {
        return (flags & Jbd2Format.TAG_FLAG_ESCAPE) != 0;
    }

    public boolean lastTag() {
        return (flags & Jbd2Format.TAG_FLAG_LAST_TAG) != 0;
    }
}
