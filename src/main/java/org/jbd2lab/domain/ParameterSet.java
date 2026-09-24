package org.jbd2lab.domain;

import java.util.Map;

/**
 * Replay parameters chosen by the filesystem team. These are source documents in their own
 * right: revising them creates a new source version and propagates through the dependency graph.
 *
 * @param crashAtJournalBlock exclusive replay boundary (journal-block coordinate); blocks at or
 *                            after this ring slot are treated as not-yet-durable (the crash point)
 * @param respectRevoke       false only in diagnostic "ignore revoke" branch plans
 * @param checksumStrict      false allows checksum-failed candidates to be marked playable
 */
public record ParameterSet(long crashAtJournalBlock, boolean respectRevoke, boolean checksumStrict,
                           Map<String, String> extra) {
    public static ParameterSet initial(long crashAtJournalBlock) {
        return new ParameterSet(crashAtJournalBlock, true, true, Map.of());
    }
}
