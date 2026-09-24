package lab.jbd2.model;

/** One retained original journal block: bytes are never normalised in the source layer. */
public record RawBlock(long physical, byte[] bytes, String origin, String note) {
}
