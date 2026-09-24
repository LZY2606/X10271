package com.journalreplay.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine-readable judgement events. Every rule the lab enforces emits one of
 * these stable codes so that reviews, archives and exports can refer to the
 * exact basis of a decision.
 */
public record Events(String code, String severity, Map<String, Object> data) {

    // --- core rule codes (the four guarded semantics) ---
    /** Only transactions with a complete, checksum-valid commit may be replayed. */
    public static final String RULE_COMMIT_REQUIRED = "RULE_COMMIT_REQUIRED";
    /** Revoke records take effect by wrap-aware sequence range [recordSeq, +inf). */
    public static final String RULE_REVOKE_SEQ_RANGE = "RULE_REVOKE_SEQ_RANGE";
    /** Ring/sequence wrap must use wrap-aware ordering, never plain integer sort. */
    public static final String RULE_WRAP_AWARE_ORDER = "RULE_WRAP_AWARE_ORDER";
    /** After a checksum failure the boundary stays uncertain; candidates are kept. */
    public static final String RULE_CHECKSUM_BOUNDARY = "RULE_CHECKSUM_BOUNDARY";

    // --- scan / replay observation codes ---
    public static final String SCAN_RING_WRAP = "SCAN_RING_WRAP";
    public static final String SCAN_SEQ_WRAP = "SCAN_SEQ_WRAP";
    public static final String SCAN_BAD_MAGIC = "SCAN_BAD_MAGIC";
    public static final String SCAN_SEQ_GAP = "SCAN_SEQ_GAP";
    public static final String SCAN_TX_COMMITTED = "SCAN_TX_COMMITTED";
    public static final String SCAN_TX_INCOMPLETE = "SCAN_TX_INCOMPLETE";
    public static final String SCAN_CHECKSUM_FAIL = "SCAN_CHECKSUM_FAIL";
    public static final String SCAN_INVALID_BLOCK_SIZE = "SCAN_INVALID_BLOCK_SIZE";
    public static final String SCAN_REVOKE_RECORDED = "SCAN_REVOKE_RECORDED";
    public static final String REPLAY_APPLY = "REPLAY_APPLY";
    public static final String REPLAY_REVOKE_HIT = "REPLAY_REVOKE_HIT";
    public static final String REPLAY_DUPLICATE_BLOCK = "REPLAY_DUPLICATE_BLOCK";
    public static final String REPLAY_IDEMPOTENT = "REPLAY_IDEMPOTENT";
    public static final String REPLAY_CANDIDATE_KEPT = "REPLAY_CANDIDATE_KEPT";
    public static final String DIAG_QUANT_KEPT_RAW = "DIAG_QUANT_KEPT_RAW";

    public static Events of(String code, String severity, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return new Events(code, severity, m);
    }

    public static Events info(String code, Object... kv) { return of(code, "info", kv); }
    public static Events warn(String code, Object... kv) { return of(code, "warn", kv); }
    public static Events error(String code, Object... kv) { return of(code, "error", kv); }
}
