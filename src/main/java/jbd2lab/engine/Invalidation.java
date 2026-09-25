package jbd2lab.engine;

import java.util.List;

/**
 * Why a derived node is still valid or must be recomputed after a source change.
 */
public record Invalidation(
        String nodeId,
        boolean stillValid,
        List<String> changedSources,
        List<String> dependencyPath,
        String reason) {
}
