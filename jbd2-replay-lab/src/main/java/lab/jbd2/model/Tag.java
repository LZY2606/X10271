package lab.jbd2.model;

/** A single descriptor tag: which filesystem block one following data block maps to. */
public record Tag(long fsBlock, long dataPhysical, boolean lastTag, boolean escaped) {
}
