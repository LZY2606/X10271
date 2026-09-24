package org.journalreplay.jbd2;

/**
 * Raised when a declared block size cannot be normalised (not a power of two,
 * or outside the ext4-supported 1024..65536 byte window). The raw value is
 * preserved on the exception so callers can keep it in place with a diagnostic
 * instead of dropping it.
 */
public class InvalidBlockSizeException extends Exception {
    public final long rawValue;
    public final String unit;

    public InvalidBlockSizeException(long rawValue, String unit, String message) {
        super(message);
        this.rawValue = rawValue;
        this.unit = unit;
    }

    /** Normalises a raw block-size declaration, or throws with the raw value attached. */
    public static int normalise(long rawValue, String unit) throws InvalidBlockSizeException {
        if (!"bytes".equals(unit)) {
            throw new InvalidBlockSizeException(rawValue, unit,
                    "unsupported unit '" + unit + "' for block size; value kept raw");
        }
        if (rawValue < 1024 || rawValue > 65536 || (rawValue & (rawValue - 1)) != 0) {
            throw new InvalidBlockSizeException(rawValue, unit,
                    "block size " + rawValue + " bytes is not a power of two in [1024, 65536]");
        }
        return (int) rawValue;
    }
}
