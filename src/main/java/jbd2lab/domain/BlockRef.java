package jbd2lab.domain;

/** A block-coverage entry: one filesystem block coordinate and the journal block supplying it. */
public record BlockRef(long fsBlock, int dataAbsBlock, long tagBlockCrc, boolean dataCrcMatches, byte[] data) {}
