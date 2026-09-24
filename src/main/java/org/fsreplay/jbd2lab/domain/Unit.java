package org.fsreplay.jbd2lab.domain;

/**
 * A measurement dimension with a canonical symbol. Every domain quantity that
 * leaves the import layer carries one of these so the UI can show the unit and
 * the coordinate basis instead of a bare number.
 */
public enum Unit {
    BYTE("B", "bytes"),
    BLOCK("blk", "filesystem block"),
    RING_BLOCK("rblk", "ring slot (journal block)"),
    RING_OFFSET("rblk@0", "zero-based ring slot offset from the physical slot 0"),
    SEQUENCE("seq", "JBD2 transaction sequence number (uint32 circular)"),
    BIT("bit", "bits"),
    NONE("-", "dimensionless"),
    VERSION("ver", "rule/version identifier"),
    EVENT("evt", "append-only event ordinal");

    private final String symbol;
    private final String description;

    Unit(String symbol, String description) {
        this.symbol = symbol;
        this.description = description;
    }

    public String symbol() {
        return symbol;
    }

    public String description() {
        return description;
    }
}
