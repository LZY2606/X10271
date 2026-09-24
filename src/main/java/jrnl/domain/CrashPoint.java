package jrnl.domain;

/**
 * Expected crash point: physical image block index where replay stops.
 * Raw representation (1-based / "before-commit@N" style strings) is kept on
 * import; this derived form uses a 0-based image index, exclusive bound.
 */
public record CrashPoint(int stopAfterImageIndex, String rawRepr, String diagnostic) {

    public boolean contains(RingPos pos) {
        return pos.imageIndex() <= stopAfterImageIndex;
    }
}
