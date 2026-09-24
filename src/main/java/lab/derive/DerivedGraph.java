package lab.derive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Dependency-graph propagation for derived results. Only affected nodes are
 * recomputed; every node can explain why it is still valid or why it had to
 * be recomputed.
 */
public final class DerivedGraph {
    public interface Compute extends Function<Map<String, Object>, Object> {}

    public static final class Node {
        public final String key;
        public final List<String> deps;
        public final Compute compute;
        public Object value;
        public String inputFingerprint;
        public String explanation = "never computed";

        public Node(String key, List<String> deps, Compute compute) {
            this.key = key;
            this.deps = deps;
            this.compute = compute;
        }
    }

    public record ReportEntry(String key, String action, String reason) {}

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    public void add(String key, List<String> deps, Compute compute) {
        nodes.put(key, new Node(key, deps, compute));
    }

    public Object get(String key) {
        Node n = nodes.get(key);
        return n == null ? null : n.value;
    }

    public Node node(String key) { return nodes.get(key); }

    /** Recomputes only nodes whose dependency fingerprint changed. */
    public List<ReportEntry> propagate() {
        List<ReportEntry> report = new ArrayList<>();
        for (String key : topoOrder()) {
            Node n = nodes.get(key);
            Map<String, Object> inputs = new HashMap<>();
            for (String d : n.deps) inputs.put(d, nodes.get(d).value);
            String fp = fingerprint(inputs);
            if (n.value != null && fp.equals(n.inputFingerprint)) {
                n.explanation = "still valid: dependency fingerprint unchanged";
                report.add(new ReportEntry(key, "kept", n.explanation));
            } else {
                String why = n.value == null ? "first computation"
                        : "recomputed: dependency fingerprint changed";
                n.value = n.compute.apply(inputs);
                n.inputFingerprint = fp;
                n.explanation = why;
                report.add(new ReportEntry(key, "recomputed", why));
            }
        }
        return report;
    }

    private List<String> topoOrder() {
        Map<String, Integer> indeg = new HashMap<>();
        for (Node n : nodes.values()) {
            indeg.putIfAbsent(n.key, 0);
            for (String d : n.deps) indeg.put(n.key, indeg.getOrDefault(n.key, 0) + 1);
        }
        Deque<String> queue = new ArrayDeque<>();
        for (var e : indeg.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
        List<String> order = new ArrayList<>();
        Map<String, List<String>> users = new HashMap<>();
        for (Node n : nodes.values()) for (String d : n.deps) {
            users.computeIfAbsent(d, k -> new ArrayList<>()).add(n.key);
        }
        while (!queue.isEmpty()) {
            String k = queue.poll();
            order.add(k);
            for (String u : users.getOrDefault(k, List.of())) {
                int d = indeg.merge(u, -1, Integer::sum);
                if (d == 0) queue.add(u);
            }
        }
        return order;
    }

    private static String fingerprint(Map<String, Object> inputs) {
        StringBuilder sb = new StringBuilder();
        inputs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                sb.append(e.getKey()).append('=')
                        .append(e.getValue() == null ? "null" : e.getValue().toString()).append(';'));
        return Integer.toHexString(sb.toString().hashCode());
    }
}
