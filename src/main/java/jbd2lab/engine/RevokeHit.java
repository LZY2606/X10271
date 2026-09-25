package jbd2lab.engine;

/**
 * A revoke that suppressed a write. Range semantics: the revoke at sequence
 * {@code revokeSeq} cancels writes whose sequence is not strictly newer on the
 * uint32 ring ({@code writeSeq} is at or before {@code revokeSeq}).
 */
public record RevokeHit(
        RevokeEvent revoke,
        WriteEvent suppressedWrite,
        String basis) {
}
