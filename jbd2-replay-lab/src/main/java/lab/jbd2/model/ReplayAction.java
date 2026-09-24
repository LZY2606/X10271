package lab.jbd2.model;

/** One planned overwrite of a filesystem block. */
public record ReplayAction(
        String actionId,
        long sequence,
        long fsBlock,
        long sourcePhysical,
        ActionDecision decision,
        String reason,
        byte[] payload) {
}
