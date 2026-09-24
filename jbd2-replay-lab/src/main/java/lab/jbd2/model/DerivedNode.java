package lab.jbd2.model;

import java.util.List;

/**
 * A node in the derived graph shown on the review page. Every node carries a stable
 * identity and lists the stable identities of its direct inputs (source or derived),
 * giving the source chain for each displayed value.
 */
public record DerivedNode(
        String nodeId,
        String kind,
        String label,
        String value,
        String unit,
        List<String> inputs,
        String status,
        String rule,
        long orderIndex) {
}
