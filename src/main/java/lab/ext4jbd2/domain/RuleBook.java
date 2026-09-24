package lab.ext4jbd2.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Versioned replay rules. Every derived result records which rulebook version
 * produced it, so reviewers can trace "which rule version caused a difference".
 */
public final class RuleBook {
    public final int version;
    /** Only transactions with a complete commit block may be replayed. */
    public final boolean commitRequired;
    /** Revoke applies by sequence range: a revoke committed in txn R suppresses
     *  writes of the same fs block from txns with sequence &lt; R (mod 2^32). */
    public final boolean revokeBySequenceRange;
    /** Ring/sequence wrap uses modular comparison, never plain integer sort. */
    public final String replayOrder;          // "ring-scan" | "plain-seq-sort" (known-wrong alternative)
    /** After a checksum failure the boundary stays uncertain; all legal
     *  candidates are kept instead of collapsing to one answer. */
    public final boolean checksumBoundaryUncertain;
    /** Allowed filesystem block sizes in bytes. */
    public final List<Long> blockSizeAllowlist;
    /** Duplicate fs block writes: last write in replay order wins. */
    public final String duplicatePolicy;      // "last-write-wins"

    public RuleBook(int version, boolean commitRequired, boolean revokeBySequenceRange,
                    String replayOrder, boolean checksumBoundaryUncertain,
                    List<Long> blockSizeAllowlist, String duplicatePolicy) {
        this.version = version;
        this.commitRequired = commitRequired;
        this.revokeBySequenceRange = revokeBySequenceRange;
        this.replayOrder = replayOrder;
        this.checksumBoundaryUncertain = checksumBoundaryUncertain;
        this.blockSizeAllowlist = blockSizeAllowlist;
        this.duplicatePolicy = duplicatePolicy;
    }

    public static RuleBook defaults(int version) {
        return new RuleBook(version, true, true, "ring-scan", true,
                List.of(1024L, 2048L, 4096L), "last-write-wins");
    }

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("version", version);
        n.put("commitRequired", commitRequired);
        n.put("revokeBySequenceRange", revokeBySequenceRange);
        n.put("replayOrder", replayOrder);
        n.put("checksumBoundaryUncertain", checksumBoundaryUncertain);
        n.put("duplicatePolicy", duplicatePolicy);
        var arr = n.putArray("blockSizeAllowlist");
        blockSizeAllowlist.forEach(arr::add);
        return n;
    }

    public static RuleBook fromJson(JsonNode n) {
        var allow = new java.util.ArrayList<Long>();
        n.path("blockSizeAllowlist").forEach(x -> allow.add(x.asLong()));
        return new RuleBook(
                n.path("version").asInt(1),
                n.path("commitRequired").asBoolean(true),
                n.path("revokeBySequenceRange").asBoolean(true),
                n.path("replayOrder").asText("ring-scan"),
                n.path("checksumBoundaryUncertain").asBoolean(true),
                allow,
                n.path("duplicatePolicy").asText("last-write-wins"));
    }
}
