package org.jbd2lab.scan;

/** One descriptor entry with the journal coordinate of its data payload. */
public record TagEntry(int index, long fsBlock, long dataRingIndex, boolean deleted,
                       boolean escape, long tagCrc) {}
