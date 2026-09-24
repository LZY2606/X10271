package lab.ext4jbd2.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Result of scanning the journal ring: transactions, anomalies and observations. */
public final class ScanResult {
    public static final class Txn {
        public final long sequence;
        public final int descCoord;
        public final List<Integer> dataCoords = new ArrayList<>();
        public Integer revokeCoord;
        public List<Long> revokeBlocks = List.of();
        public Integer commitCoord;
        public String status;          // COMMITTED | INCOMPLETE | UNCERTAIN
        public final List<Long> fsBlocks = new ArrayList<>();

        public Txn(long sequence, int descCoord) {
            this.sequence = sequence;
            this.descCoord = descCoord;
            this.status = "INCOMPLETE";
        }

        public ObjectNode toJson() {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            n.put("sequence", Hex.u32(sequence));
            n.put("sequenceUnsigned", sequence);
            n.put("descCoord", descCoord);
            var dc = n.putArray("dataCoords");
            dataCoords.forEach(dc::add);
            if (revokeCoord != null) n.put("revokeCoord", revokeCoord);
            if (commitCoord != null) n.put("commitCoord", commitCoord);
            n.put("status", status);
            var fs = n.putArray("fsBlocks");
            fsBlocks.forEach(fs::add);
            if (revokeBlocks != null) {
                var rv = n.putArray("revokeBlocks");
                revokeBlocks.forEach(rv::add);
            }
            return n;
        }
    }

    public static final class Candidate {
        public final String id;
        public final String label;
        public final String rationale;

        public Candidate(String id, String label, String rationale) {
            this.id = id;
            this.label = label;
            this.rationale = rationale;
        }

        public ObjectNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("id", id);
            o.put("label", label);
            o.put("rationale", rationale);
            return o;
        }
    }

    public static final class Anomaly {
        public final String id;
        public final String kind;      // HALF_TRANSACTION | CHECKSUM_BREAK | INVALID_BLOCK_SIZE | ORPHAN_COMMIT
        public final int coordinate;
        public final Long sequence;
        public final String message;
        public final List<Candidate> candidates = new ArrayList<>();

        public Anomaly(String id, String kind, int coordinate, Long sequence, String message) {
            this.id = id;
            this.kind = kind;
            this.coordinate = coordinate;
            this.sequence = sequence;
            this.message = message;
        }

        public ObjectNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("id", id);
            o.put("kind", kind);
            o.put("coordinate", coordinate);
            if (sequence != null) o.put("sequence", Hex.u32(sequence));
            o.put("message", message);
            var cs = o.putArray("candidates");
            candidates.forEach(c -> cs.add(c.toJson()));
            return o;
        }
    }

    public static final class Observation {
        public final String kind;      // RING_WRAP | SEQ_WRAP | CRASH_HORIZON | END_OF_LOG
        public final int coordinate;
        public final String detail;

        public Observation(String kind, int coordinate, String detail) {
            this.kind = kind;
            this.coordinate = coordinate;
            this.detail = detail;
        }

        public ObjectNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("kind", kind);
            o.put("coordinate", coordinate);
            o.put("detail", detail);
            return o;
        }
    }

    public final List<Txn> txns = new ArrayList<>();
    public final List<Anomaly> anomalies = new ArrayList<>();
    public final List<Observation> observations = new ArrayList<>();
    public final List<Integer> ringWalk = new ArrayList<>();
    public int endOfLog = -1;
    /** Present after a CHECKSUM_BREAK: legal ring continuation for candidate "torn-continue". */
    public ScanResult continuation;
    public boolean aborted;
    public String abortReason;

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        var w = n.putArray("ringWalk");
        ringWalk.forEach(w::add);
        var t = n.putArray("transactions");
        txns.forEach(x -> t.add(x.toJson()));
        var a = n.putArray("anomalies");
        anomalies.forEach(x -> a.add(x.toJson()));
        var o = n.putArray("observations");
        observations.forEach(x -> o.add(x.toJson()));
        n.put("endOfLog", endOfLog);
        n.put("aborted", aborted);
        if (abortReason != null) n.put("abortReason", abortReason);
        if (continuation != null) n.set("continuation", continuation.toJson());
        return n;
    }

    public Anomaly anomalyById(String id) {
        for (Anomaly a : anomalies) if (a.id.equals(id)) return a;
        return null;
    }
}
