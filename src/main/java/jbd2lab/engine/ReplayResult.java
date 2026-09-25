package jbd2lab.engine;

import java.util.List;

/** Outcome of replay planning: a deterministic plan (possibly with candidates) or refusals. */
public record ReplayResult(
        ReplayPlan plan,
        List<String> errors,
        boolean refused) {

    public static ReplayResult refused(List<String> errors) {
        return new ReplayResult(null, List.copyOf(errors), true);
    }
}
