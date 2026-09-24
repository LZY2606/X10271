package com.journalreplay.domain;

import java.util.Map;
import java.util.TreeMap;

/**
 * Imported source of truth: journal area blocks, filesystem block snapshot,
 * superblock and the expected crash point. Everything is stored raw; the
 * derived layer normalises.
 *
 * Coordinates:
 *  - journal block index: unit=block, basis=journal area start (block 0 = superblock)
 *  - filesystem block address: unit=block, basis=device start
 *  - crash point: number of journal blocks (ring order from s_start) persisted before crash
 */
public record JournalImage(
        Quant blockSize,
        Quant ringStart,
        Quant firstSequence,
        Quant maxlen,
        Quant crashPoint,
        String checksumVersion,
        Map<Integer, byte[]> journalBlocks,
        Map<Long, byte[]> fsSnapshot) {

    public JournalImage {
        journalBlocks = Map.copyOf(journalBlocks);
        fsSnapshot = Map.copyOf(fsSnapshot);
    }

    public byte[] journalBlock(int loc) {
        return journalBlocks.get(loc);
    }

    /** Ring positions are [first, maxlen): pos+1 wraps back to first (kernel wrap()). */
    public int wrap(int pos) {
        int first = ringStart.normalized().intValue();
        int max = maxlen.normalized().intValue();
        int next = pos + 1;
        return next >= max ? first : next;
    }

    public Map<Long, byte[]> sortedFsSnapshot() {
        return new TreeMap<>(fsSnapshot);
    }
}
