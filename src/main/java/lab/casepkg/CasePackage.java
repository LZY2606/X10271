package lab.casepkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lab.derive.DerivedGraph;
import lab.journal.JournalParser;
import lab.journal.ReplayEngine;
import lab.model.Model.JBlock;
import lab.model.Model.JournalSpec;
import lab.model.Model.Quantity;
import lab.model.Model.ReplayPlan;
import lab.store.Store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the offline domain case package ("ext4 日志演算室") and seeds the store:
 * baseline version, single-source mutations (the counterexample archive),
 * one parameter revision, one local source change and one conflicting human
 * decision. Machine-readable judgment events are recorded for every rule call.
 */
public final class CasePackage {
    private static final ObjectMapper M = new ObjectMapper();

    public final JsonNode root;
    public final Path path;

    private CasePackage(JsonNode root, Path path) {
        this.root = root;
        this.path = path;
    }

    public static CasePackage load(Path path) {
        try {
            return new CasePackage(M.readTree(Files.readString(path)), path);
        } catch (Exception e) {
            throw new RuntimeException("cannot load case package " + path, e);
        }
    }

    // ------------------------------------------------------------ JSON <-> model

    public static Quantity quantityFromJson(JsonNode q) {
        if (q == null) return null;
        Double norm = q.has("normalized") && q.get("normalized").isNumber()
                ? q.get("normalized").doubleValue() : null;
        return new Quantity(text(q, "raw"), text(q, "unit"), text(q, "basis"), norm, text(q, "diagnostic"));
    }

    public static JournalSpec journalFromJson(JsonNode j) {
        List<JBlock> blocks = new ArrayList<>();
        for (JsonNode b : j.get("blocks")) {
            List<Long> tags = longs(b.get("tags"));
            List<Long> revoke = longs(b.get("revoke"));
            blocks.add(new JBlock(b.get("slot").asInt(), b.get("type").asText(),
                    b.get("sequence").asLong(), tags, revoke,
                    text(b, "payloadHex"), text(b, "checksumStored"), text(b, "checksumVersion"),
                    false));
        }
        Map<String, Quantity> extra = new LinkedHashMap<>();
        if (j.has("extraQuantities")) {
            j.get("extraQuantities").fields().forEachRemaining(e ->
                    extra.put(e.getKey(), quantityFromJson(e.getValue())));
        }
        return new JournalSpec(j.get("id").asText(), quantityFromJson(j.get("blockSize")),
                quantityFromJson(j.get("ringStart")), j.get("ringBlocks").asInt(),
                j.get("sequenceBase").asLong(), text(j, "checksumVersion"), blocks, extra);
    }

    public static ObjectNode journalToJson(JournalSpec spec) {
        ObjectNode j = M.createObjectNode();
        j.put("id", spec.id());
        j.set("blockSize", quantityToJson(spec.blockSize()));
        j.set("ringStart", quantityToJson(spec.ringStart()));
        j.put("ringBlocks", spec.ringBlocks());
        j.put("sequenceBase", spec.sequenceBase());
        j.put("checksumVersion", spec.checksumVersion());
        ArrayNode blocks = j.putArray("blocks");
        for (JBlock b : spec.blocks()) {
            ObjectNode o = blocks.addObject();
            o.put("slot", b.slot());
            o.put("type", b.type());
            o.put("sequence", b.sequence());
            if (b.tags() != null && !b.tags().isEmpty()) o.putPOJO("tags", b.tags());
            if (b.revoke() != null && !b.revoke().isEmpty()) o.putPOJO("revoke", b.revoke());
            if (b.payloadHex() != null) o.put("payloadHex", b.payloadHex());
            if (b.checksumStored() != null) o.put("checksumStored", b.checksumStored());
            if (b.checksumVersion() != null) o.put("checksumVersion", b.checksumVersion());
        }
        if (spec.extraQuantities() != null && !spec.extraQuantities().isEmpty()) {
            ObjectNode extra = j.putObject("extraQuantities");
            spec.extraQuantities().forEach((k, q) -> extra.set(k, quantityToJson(q)));
        }
        return j;
    }

    static ObjectNode quantityToJson(Quantity q) {
        ObjectNode o = M.createObjectNode();
        if (q == null) return o;
        o.put("raw", q.raw());
        o.put("unit", q.unit());
        o.put("basis", q.basis());
        if (q.normalized() != null) o.put("normalized", q.normalized());
        if (q.diagnostic() != null) o.put("diagnostic", q.diagnostic());
        return o;
    }

    public static ObjectNode planToJson(ReplayPlan p) {
        ObjectNode o = M.createObjectNode();
        o.put("journalId", p.journalId);
        if (p.crashAtSlot != null) o.put("crashAtSlot", p.crashAtSlot);
        o.putPOJO("diagnostics", p.diagnostics);
        o.put("blockSizeValid", p.blockSizeValid);
        o.put("idempotent", p.idempotent);
        if (p.checksumBreakAtSequence != null) o.put("checksumBreakAtSequence", p.checksumBreakAtSequence);
        ArrayNode txns = o.putArray("txns");
        for (var t : p.txns) {
            ObjectNode tn = txns.addObject();
            tn.put("sequence", t.sequence);
            tn.put("ringPos", t.ringPos);
            tn.put("startSlot", t.startSlot);
            tn.put("state", t.state);
            tn.put("hasCommit", t.hasCommit);
            tn.put("checksumOk", t.checksumOk);
        }
        o.putPOJO("replayable", p.replayable.stream().map(t -> t.sequence).toList());
        ObjectNode map = o.putObject("blockMap");
        p.blockMap.forEach(map::put);
        ArrayNode hits = o.putArray("revokeHits");
        for (var h : p.revokeHits) {
            hits.addObject().put("fsblock", h.fsblock())
                    .put("revokedFromSequence", h.revokedFromSequence())
                    .put("revokedBySequence", h.revokedBySequence());
        }
        ArrayNode ows = o.putArray("overwrites");
        for (var w : p.overwrites) {
            ows.addObject().put("fsblock", w.fsblock())
                    .put("fromSequence", w.fromSequence())
                    .put("toSequence", w.toSequence());
        }
        ArrayNode cands = o.putArray("candidates");
        for (var c : p.candidates) {
            ObjectNode cn = cands.addObject();
            cn.put("id", c.id());
            cn.put("label", c.label());
            cn.putPOJO("includedSequences", c.includedSequences());
            cn.put("chosen", c.chosen());
            ObjectNode cm = cn.putObject("blockMap");
            c.blockMap().forEach(cm::put);
        }
        o.putPOJO("events", p.events);
        return o;
    }

    // ------------------------------------------------------------------- seeding

    /** Seeds the full narrative into an empty store. */
    public void seed(Store store) {
        JournalParser parser = new JournalParser();
        ReplayEngine engine = new ReplayEngine();

        // 1. Baseline import: everything preserved.
        JournalSpec baseline = journalFromJson(root.get("baseline"));
        ReplayPlan basePlan = engine.plan(parser.parse(baseline), null);
        ObjectNode basePayload = M.createObjectNode();
        basePayload.put("stage", "baseline-import");
        basePayload.set("journal", journalToJson(baseline));
        basePayload.set("plan", planToJson(basePlan));
        basePayload.set("quantities", root.get("quantities"));
        store.saveWithId("v-baseline", null, "baseline", "基线导入：原块字节/块大小/环起点/sequence/关系/checksum 版本均保留",
                basePayload);
        store.addEventWithId("evt-baseline", "v-baseline", "BASELINE_IMPORTED",
                Map.of("journalId", baseline.id(), "blocks", baseline.blocks().size(),
                        "checksumVersion", String.valueOf(baseline.checksumVersion())));

        // 2. Counterexample archive: one direct source change per mutation.
        for (JsonNode mut : root.get("mutations")) {
            String id = mut.get("id").asText();
            JournalSpec spec = journalFromJson(mut.get("journal"));
            Long crash = mut.has("crashAtSlot") ? mut.get("crashAtSlot").asLong() : null;
            ReplayPlan plan = engine.plan(parser.parse(spec), crash);
            ObjectNode payload = M.createObjectNode();
            payload.put("stage", "mutation");
            payload.put("mutationId", id);
            payload.put("change", mut.get("change").asText());
            payload.set("journal", journalToJson(spec));
            payload.set("plan", planToJson(plan));
            if (mut.has("expected")) payload.set("expected", mut.get("expected"));
            String vid = "v-mut-" + id;
            store.saveWithId(vid, "v-baseline", "mutation",
                    "反例: " + mut.get("title").asText(), payload);
            // Machine-readable judgment events for the rule basis.
            for (Map<String, Object> e : plan.events) {
                store.addEvent(vid, String.valueOf(e.get("type")), e);
            }
            // Adopted and unadopted results are both kept as candidates.
            if (!plan.candidates.isEmpty()) {
                String decisionId = "decision-" + id;
                for (var c : plan.candidates) {
                    ObjectNode cv = M.createObjectNode();
                    cv.put("candidateId", c.id());
                    cv.put("label", c.label());
                    cv.putPOJO("includedSequences", c.includedSequences());
                    ObjectNode cm = cv.putObject("blockMap");
                    c.blockMap().forEach(cm::put);
                    String cvid = "v-cand-" + id + "-" + c.id();
                    store.saveWithId(cvid, vid, "candidate", "候选: " + c.label(), cv);
                    store.addCandidate(decisionId, cvid, false);
                }
                store.addEvent(vid, "CANDIDATES_KEPT",
                        Map.of("decisionId", decisionId, "count", plan.candidates.size()));
            }
        }

        // 3. Revisions: parameter revision, local source change, conflicting decision.
        seedRevisions(store, parser, engine, baseline);
    }

    private void seedRevisions(Store store, JournalParser parser, ReplayEngine engine, JournalSpec baseline) {
        JsonNode revs = root.get("revisions");
        if (revs == null) return;
        for (JsonNode rev : revs) {
            String kind = rev.get("kind").asText();
            String id = rev.get("id").asText();
            switch (kind) {
                case "parameter" -> {
                    long crash = rev.get("param").get("crashAtSlot").asLong();
                    ReplayPlan plan = engine.plan(parser.parse(baseline), crash);
                    ObjectNode payload = M.createObjectNode();
                    payload.put("stage", "parameter-revision");
                    payload.put("crashAtSlot", crash);
                    payload.set("journal", journalToJson(baseline));
                    payload.set("plan", planToJson(plan));
                    store.saveWithId("v-" + id, "v-baseline", "revision",
                            "参数修订: " + rev.get("title").asText(), payload);
                    store.addEvent("v-" + id, "PARAMETER_REVISION",
                            Map.of("crashAtSlot", crash, "basis", "crash point parameter changed"));
                }
                case "source" -> {
                    int slot = rev.get("change").get("slot").asInt();
                    String newPayload = rev.get("change").get("payloadHex").asText();
                    JournalSpec changed = replacePayload(baseline, slot, newPayload);
                    // Dependency propagation: only nodes depending on the changed block recompute.
                    DerivedGraph g = new DerivedGraph();
                    g.add("source-blocks", List.of(), in -> baseline.blocks().toString());
                    g.add("changed-block", List.of(), in -> slot + ":" + newPayload);
                    g.add("parse", List.of("source-blocks", "changed-block"),
                            in -> "parsed@" + in.get("changed-block"));
                    g.add("plan", List.of("parse"), in -> "plan of " + in.get("parse"));
                    g.add("render", List.of("plan"), in -> "svg of " + in.get("plan"));
                    g.propagate();
                    var report = g.propagate(); // second pass: everything kept
                    ObjectNode payload = M.createObjectNode();
                    payload.put("stage", "local-source-change");
                    payload.put("changedSlot", slot);
                    payload.set("journal", journalToJson(changed));
                    payload.set("plan", planToJson(engine.plan(parser.parse(changed), null)));
                    payload.putPOJO("propagation", report.stream().map(r ->
                            Map.of("key", r.key(), "action", r.action(), "reason", r.reason())).toList());
                    store.saveWithId("v-" + id, "v-baseline", "revision",
                            "局部源数据改动: " + rev.get("title").asText(), payload);
                    store.addEvent("v-" + id, "LOCAL_SOURCE_CHANGE",
                            Map.of("slot", slot, "basis", "only dependent derived nodes recomputed"));
                }
                case "decision" -> {
                    // Two reviewers edit from the same base: a resolvable conflict.
                    String left = rev.get("decision").get("left").asText();
                    String right = rev.get("decision").get("right").asText();
                    String base = store.current();
                    var r1 = store.saveWithId("v-" + id + "-left", base, "decision",
                            "人工决定 A: " + left, Map.of("choice", left, "reviewer", "reviewer-1"));
                    var r2 = store.saveWithId("v-" + id + "-right", base, "decision",
                            "人工决定 B: " + right, Map.of("choice", right, "reviewer", "reviewer-2"));
                    String decisionId = "decision-" + id;
                    store.addCandidate(decisionId, r1.versionId(), false);
                    store.addCandidate(decisionId, r2.versionId(), false);
                    if (r2.isConflict()) {
                        // Resolve in favor of reviewer-1; the unchosen candidate is preserved.
                        store.chooseCandidate(decisionId, r1.versionId());
                        store.resolveConflict(r2.conflictId(), r1.versionId());
                        store.addEvent(r1.versionId(), "CONFLICT_RESOLVED",
                                Map.of("conflictId", r2.conflictId(), "chosen", r1.versionId(),
                                        "unchosenKept", r2.versionId()));
                    }
                }
                default -> throw new IllegalArgumentException("unknown revision kind " + kind);
            }
        }
    }

    private static JournalSpec replacePayload(JournalSpec spec, int slot, String payloadHex) {
        List<JBlock> blocks = new ArrayList<>();
        for (JBlock b : spec.blocks()) {
            blocks.add(b.slot() == slot
                    ? new JBlock(b.slot(), b.type(), b.sequence(), b.tags(), b.revoke(),
                            payloadHex, lab.Crc32c.hex(b.type() + "|" + b.sequence() + "|"
                                    + (b.tags() == null ? "" : JBlock.joinLongs(b.tags())) + "|"
                                    + (b.revoke() == null ? "" : JBlock.joinLongs(b.revoke())) + "|" + payloadHex),
                            b.checksumVersion(), false)
                    : b);
        }
        return new JournalSpec(spec.id() + "-local", spec.blockSize(), spec.ringStart(),
                spec.ringBlocks(), spec.sequenceBase(), spec.checksumVersion(), blocks,
                spec.extraQuantities());
    }

    // ------------------------------------------------------------------ helpers

    private static String text(JsonNode n, String field) {
        return n != null && n.has(field) && !n.get(field).isNull() ? n.get(field).asText() : null;
    }

    private static List<Long> longs(JsonNode arr) {
        if (arr == null) return null;
        List<Long> out = new ArrayList<>();
        for (JsonNode x : arr) out.add(x.asLong());
        return out;
    }
}
