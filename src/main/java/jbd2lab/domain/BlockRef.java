package jbd2lab.domain;

/**
 * Coordinate of a journal block on the ring: physical block index plus the ring
 * offset at which it was visited. Both bases are kept so UI tables can show the
 * physical position and ring order without re-deriving one from the other.
 */
public record BlockRef(int physical, long ringOffset) {
}
