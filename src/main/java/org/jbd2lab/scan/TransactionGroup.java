package org.jbd2lab.scan;

import java.util.List;

/**
 * A transaction assembled by the physical walk.
 *
 * <p>State values:
 * COMPLETE - descriptor (and optional revoke) + commit, all framing and checksums verified;
 * HALF     - descriptor seen but the walk ended at the crash boundary before a commit;
 * BROKEN   - checksum failure inside the transaction or structurally impossible framing;
 * RESYNC   - alternative candidate restarted after a checksum break (may itself be COMPLETE).
 */
public record TransactionGroup(
        String groupId,
        long sequence,
        long descriptorRingIndex,
        Long revokeRingIndex,
        long commitRingIndex,
        List<TagEntry> tags,
        List<Long> revokedBlocks,
        String state,
        List<String> evidence,
        String candidateId) {

    public static final String COMPLETE = "COMPLETE";
    public static final String HALF = "HALF";
    public static final String BROKEN = "BROKEN";
    public static final String RESYNC = "RESYNC";

    public boolean replayable() {
        return COMPLETE.equals(state);
    }
}
