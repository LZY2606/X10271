package fsreplay.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency graph for incremental recompute. A change to a source node marks only its
 * transitive dependents stale; every node can explain why it is still valid or must recompute.
 */
public final class DepGraph {
    public record NodeState(String name, boolean stale, String reason) {}

    private final Map<String, List<String>> dependents = new LinkedHashMap<>(); // node -> nodes depending on it
    private final Map<String, Boolean> stale = new LinkedHashMap<>();
    private final Map<String, String> reasons = new LinkedHashMap<>();

    public DepGraph() {
        edge("superblock", "scan");
        edge("journalImage", "scan");
        edge("checksumVersion", "scan");
        edge("crashPoint", "scan");
        edge("scan", "plan");
        edge("plan", "derivedMap");
        edge("scan", "journalRingView");
        edge("plan", "coverageView");
        edge("scan", "revokeView");
        stale.keySet().forEach(k -> stale.put(k, false));
    }

    private void edge(String from, String to) {
        dependents.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        stale.putIfAbsent(from, false);
        stale.putIfAbsent(to, false);
        reasons.putIfAbsent(from, "source");
        reasons.putIfAbsent(to, "up to date");
    }

    /** Mark a source changed; only transitive dependents become stale. */
    public List<String> invalidate(String source) {
        List<String> affected = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            String n = queue.poll();
            for (String dep : dependents.getOrDefault(n, List.of())) {
                if (!stale.get(dep)) {
                    stale.put(dep, true);
                    reasons.put(dep, "needs recompute: upstream '" + n + "' changed");
                    affected.add(dep);
                    queue.add(dep);
                }
            }
        }
        return affected;
    }

    public void markComputed(String node) {
        stale.put(node, false);
        reasons.put(node, "recomputed from current inputs");
    }

    public String explain(String node) {
        return stale.getOrDefault(node, false)
                ? "STALE — " + reasons.get(node)
                : "VALID — " + reasons.getOrDefault(node, "up to date");
    }

    public List<NodeState> states() {
        List<NodeState> out = new ArrayList<>();
        for (String n : stale.keySet()) out.add(new NodeState(n, stale.get(n), reasons.get(n)));
        return out;
    }
}
