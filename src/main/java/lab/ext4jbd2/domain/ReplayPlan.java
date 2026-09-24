package lab.ext4jbd2.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A replay plan: ordered block writes, revoke hits, coverage and uncertainties. */
public final class ReplayPlan {
    public static final class Write {
        public final long fsBlock;
        public final int dataCoord;      // journal-block-index holding the new content
        public final long txnSeq;
        public String status;            // effective | revoked | superseded
        public Long revokeTxnSeq;        // set when status == revoked
        public Integer revokeCoord;

        public Write(long fsBlock, int dataCoord, long txnSeq) {
            this.fsBlock = fsBlock;
            this.dataCoord = dataCoord;
            this.txnSeq = txnSeq;
            this.status = "effective";
        }

        public ObjectNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("fsBlock", fsBlock);
            o.put("dataCoord", dataCoord);
            o.put("txnSeq", Hex.u32(txnSeq));
            o.put("status", status);
            if (revokeTxnSeq != null) o.put("revokedByTxn", Hex.u32(revokeTxnSeq));
            if (revokeCoord != null) o.put("revokedAt", revokeCoord);
            return o;
        }
    }

    public static final class RevokeHit {
        public final long fsBlock;
        public final long victimTxnSeq;
        public final int victimCoord;
        public final long revokeTxnSeq;
        public final int revokeCoord;

        public RevokeHit(long fsBlock, long victimTxnSeq, int victimCoord,
                         long revokeTxnSeq, int revokeCoord) {
            this.fsBlock = fsBlock;
            this.victimTxnSeq = victimTxnSeq;
            this.victimCoord = victimCoord;
            this.revokeTxnSeq = revokeTxnSeq;
            this.revokeCoord = revokeCoord;
        }

        public ObjectNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("fsBlock", fsBlock);
            o.put("victimTxnSeq", Hex.u32(victimTxnSeq));
            o.put("victimCoord", victimCoord);
            o.put("revokeTxnSeq", Hex.u32(revokeTxnSeq));
            o.put("revokeCoord", revokeCoord);
            return o;
        }
    }

    public int ruleVersion;
    public boolean provisional;
    public final Map<String, String> selection = new LinkedHashMap<>(); // anomalyId -> candidateId
    public final List<Write> writes = new ArrayList<>();
    public final List<RevokeHit> revokeHits = new ArrayList<>();
    /** fsBlock -> ordered write indexes (replay order). */
    public final Map<Long, List<Integer>> coverage = new LinkedHashMap<>();
    /** anomalyId -> candidate ids (all legal candidates retained). */
    public final Map<String, List<String>> uncertainties = new LinkedHashMap<>();
    /** candidateKey -> writes for that candidate (alternatives kept, never dropped). */
    public final Map<String, List<Write>> alternatives = new LinkedHashMap<>();
    public final List<String> notes = new ArrayList<>();

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("ruleVersion", ruleVersion);
        n.put("provisional", provisional);
        ObjectNode sel = n.putObject("selection");
        selection.forEach(sel::put);
        ArrayNode w = n.putArray("writes");
        writes.forEach(x -> w.add(x.toJson()));
        ArrayNode r = n.putArray("revokeHits");
        revokeHits.forEach(x -> r.add(x.toJson()));
        ObjectNode cov = n.putObject("coverage");
        coverage.forEach((fs, idxs) -> {
            ArrayNode arr = cov.putArray(Long.toString(fs));
            idxs.forEach(arr::add);
        });
        ObjectNode unc = n.putObject("uncertainties");
        uncertainties.forEach((a, cs) -> {
            ArrayNode arr = unc.putArray(a);
            cs.forEach(arr::add);
        });
        ObjectNode alt = n.putObject("alternatives");
        alternatives.forEach((key, ws) -> {
            ArrayNode arr = alt.putArray(key);
            ws.forEach(x -> arr.add(x.toJson()));
        });
        ArrayNode nt = n.putArray("notes");
        notes.forEach(nt::add);
        return n;
    }
}
