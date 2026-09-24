package gsb.journal.domain;

/**
 * Physical/coordinate basis for a domain quantity. Every imported or derived
 * numeric value carries one of these so the UI can never show a unitless byte.
 */
public enum Unit {
    BYTE("byte", "bytes"),
    JOURNAL_BLOCK("journal block", "blocks within the journal ring"),
    FS_BLOCK("filesystem block", "blocks addressed by blocknr in the filesystem"),
    TRANSACTION_SEQUENCE("sequence", "32-bit JBD2 transaction id with wrap"),
    NONE("raw", "value whose representation could not be normalised"),
    JSON_POINTER("path", "coordinate inside an imported manifest (JSON)");

    private final String shortName;
    private final String description;

    Unit(String shortName, String description) {
        this.shortName = shortName;
        this.description = description;
    }

    public String shortName() {
        return shortName;
    }

    public String description() {
        return description;
    }
}
