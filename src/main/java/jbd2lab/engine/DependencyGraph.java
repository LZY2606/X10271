package jbd2lab.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency graph of source materials -> derived nodes. A source revision marks
 * only the transitive downstream set dirty; everything outside that set keeps its
 * previous version and can explain why.
 */
public final class DependencyGraph {

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> edges = new LinkedHashMap<>(); // node -> deps

    public record Node(String id, String kind, int version, long sourceVersion) {
    }

    public void addNode(String id, String kind, int version, long sourceVersion, List<String> deps) {
        nodes.put(id, new Node(id, kind, version, sourceVersion));
        edges.put(id, new ArrayList<>(deps));
    }

    public Node node(String id) {
        return nodes.get(id);
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    /** Transitive descendants (derived nodes depending, directly or indirectly, on a source). */
    public Set<String> downstream(String sourceId) {
        Set<String> dirty = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(sourceId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Map.Entry<String, List<String>> e : edges.entrySet()) {
                if (!dirty.contains(e.getKey()) && e.getValue().contains(cur)) {
                    dirty.add(e.getKey());
                    queue.add(e.getKey());
                }
            }
        }
        return dirty;
    }

    /**
     * Produce an invalidation report after one or more source ids change.
     * Each dirty derived node gets the shortest dependency path to a changed source.
     */
    public List<Invalidation> propagate(Set<String> changedSources) {
        Set<String> dirtyUnion = new HashSet<>();
        for (String s : changedSources) {
            dirtyUnion.addAll(downstream(s));
        }
        List<Invalidation> out = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.kind().equals("source")) {
                continue;
            }
            boolean valid = !dirtyUnion.contains(n.id());
            List<String> path = List.of();
            String reason;
            if (valid) {
                reason = "UNCHANGED: no transitive dependency on changed sources " + changedSources;
            } else {
                path = shortestPath(n.id(), changedSources);
                reason = "DIRTY: transitive dependency path " + path + " touches changed source";
            }
            out.add(new Invalidation(n.id(), valid, List.copyOf(changedSources), path, reason));
        }
        return out;
    }

    private List<String> shortestPath(String nodeId, Set<String> targets) {
        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        Set<String> seen = new HashSet<>(Set.of(nodeId));
        String hit = null;
        outer:
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String dep : edges.getOrDefault(cur, List.of())) {
                if (targets.contains(dep)) {
                    parent.put(dep, cur);
                    hit = dep;
                    break outer;
                }
                if (seen.add(dep)) {
                    parent.put(dep, cur);
                    queue.add(dep);
                }
            }
        }
        if (hit == null) {
            return List.of();
        }
        List<String> path = new ArrayList<>();
        String cur = hit;
        while (cur != null) {
            path.add(cur);
            cur = parent.get(cur);
        }
        java.util.Collections.reverse(path);
        return path;
    }
}
