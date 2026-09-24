package jrnl.domain;

/**
 * A recorded mask decision. Sequence-range semantics: a write at sequence
 * {@code writeSeq} for {@code fsBlock} is masked only when it is older (on the
 * ring) than the newest surviving revoke for the same block.
 */
public record RevokeHit(
        long fsBlock,
        Seq writeSeq,
        Seq revokeSeq,
        RingPos revokePos,
        RingPos writePos,
        boolean masked,
        String basis) {
}
