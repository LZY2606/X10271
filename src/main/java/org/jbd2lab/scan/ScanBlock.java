package org.jbd2lab.scan;

/**
 * One visited ring slot in deterministic walk order.
 *
 * @param walkOrder      0-based visit order of the physical ring walk
 * @param ringIndex      journal-block coordinate in the ring
 * @param wrapped        true if this slot was reached after the ring index wrapped
 * @param beforeCrash    true iff the slot is strictly before the configured crash boundary
 * @param blockType      Jbd2 block type constant, or -1 for unrecognised framing
 * @param sequence       declared sequence (unit u32-circular), or null
 * @param checksum       checksum verdict
 * @param note           short machine-style note (stable token, no prose-only facts)
 */
public record ScanBlock(int walkOrder, long ringIndex, boolean wrapped, boolean beforeCrash,
                        int blockType, Long sequence, CheckVerdict checksum, String note) {
    public static final int UNRECOGNISED = -1;
    public static final int PLAIN_DATA = 0;
}
