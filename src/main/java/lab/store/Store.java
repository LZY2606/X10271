package lab.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned store. Every derivation produces an independent immutable version;
 * the current pointer switches only after the version is fully written.
 * Two editors starting from the same base version produce a resolvable conflict;
 * unchosen candidates are always preserved.
 */
public final class Store {
    private static final ObjectMapper M = new ObjectMapper();

    /** Test hook invoked between the two write phases to simulate a crash. */
    public static volatile Runnable faultHook = () -> {};

    private final Database db;

    public Store(Database db) { this.db = db; }

    public record SaveResult(String versionId, String conflictId) {
        public boolean isConflict() { return conflictId != null; }
    }

    public record VersionRow(String id, String parentId, String kind, String label,
                             String status, String payload, long seq, String createdAt) {}

    public record EventRow(String id, String versionId, long seq, String type,
                           String payload, String createdAt) {}

    // ---------------------------------------------------------------- versions

    public String current() {
        try (var ps = db.conn().prepareStatement(
                "SELECT version_id FROM current_pointer WHERE name='main'")) {
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public VersionRow getVersion(String id) {
        try (var ps = db.conn().prepareStatement(
                "SELECT id,parent_id,kind,label,status,payload,seq,created_at FROM versions WHERE id=?")) {
            ps.setString(1, id);
            var rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new VersionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<VersionRow> listVersions() {
        List<VersionRow> out = new ArrayList<>();
        try (var st = db.conn().createStatement();
             var rs = st.executeQuery(
                     "SELECT id,parent_id,kind,label,status,payload,seq,created_at FROM versions ORDER BY seq")) {
            while (rs.next()) {
                out.add(new VersionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8)));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Saves a new version. If the current pointer moved past expectedBase since
     * the caller read it, both versions are kept and a resolvable conflict opens.
     */
    public SaveResult save(String expectedBase, String kind, String label, Object payload) {
        return saveWithId("v-" + UUID.randomUUID(), expectedBase, kind, label, payload);
    }

    public SaveResult saveWithId(String id, String expectedBase, String kind, String label, Object payload) {
        String json = toJson(payload);
        String now = Instant.now().toString();
        long seq = nextSeq("versions");
        // Phase 1: durable WRITING row (committed separately to make crash windows real).
        exec("INSERT INTO versions(id,parent_id,kind,label,status,payload,seq,created_at)" +
                " VALUES(?,?,?,?,'WRITING',?,?,?)",
                id, expectedBase, kind, label, json, seq, now);
        faultHook.run();
        String current = current();
        boolean conflict = current != null && expectedBase != null && !current.equals(expectedBase);
        String conflictId = null;
        // Phase 2: atomic completion + pointer switch (single transaction).
        try {
            db.conn().setAutoCommit(false);
            exec("UPDATE versions SET status='COMPLETE' WHERE id=?", id);
            if (!conflict) {
                exec("INSERT INTO current_pointer(name,version_id) VALUES('main',?) " +
                        "ON CONFLICT(name) DO UPDATE SET version_id=excluded.version_id", id);
            }
            db.conn().commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            quietAutoCommit();
        }
        if (conflict) {
            conflictId = "c-" + UUID.randomUUID();
            exec("INSERT INTO conflicts(id,base_version_id,left_version_id,right_version_id,status,created_at)" +
                    " VALUES(?,?,?,?,'OPEN',?)", conflictId, expectedBase, current, id, now);
        }
        return new SaveResult(id, conflictId);
    }

    public void resolveConflict(String conflictId, String chosenVersionId) {
        try {
            db.conn().setAutoCommit(false);
            exec("UPDATE conflicts SET status='RESOLVED', resolved_version_id=? WHERE id=?",
                    chosenVersionId, conflictId);
            exec("INSERT INTO current_pointer(name,version_id) VALUES('main',?) " +
                    "ON CONFLICT(name) DO UPDATE SET version_id=excluded.version_id", chosenVersionId);
            db.conn().commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            quietAutoCommit();
        }
    }

    public List<Map<String, Object>> listConflicts() {
        return queryMaps("SELECT * FROM conflicts ORDER BY created_at");
    }

    // ------------------------------------------------------------------ events

    public String addEvent(String versionId, String type, Object payload) {
        String id = "evt-" + UUID.randomUUID();
        addEventWithId(id, versionId, type, payload);
        return id;
    }

    public void addEventWithId(String id, String versionId, String type, Object payload) {
        exec("INSERT INTO events(id,version_id,seq,type,payload,created_at) VALUES(?,?,?,?,?,?)",
                id, versionId, nextSeq("events"), type, toJson(payload), Instant.now().toString());
    }

    public List<EventRow> listEvents() {
        List<EventRow> out = new ArrayList<>();
        try (var st = db.conn().createStatement();
             var rs = st.executeQuery("SELECT id,version_id,seq,type,payload,created_at FROM events ORDER BY seq")) {
            while (rs.next()) {
                out.add(new EventRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public EventRow getEvent(String id) {
        try (var ps = db.conn().prepareStatement(
                "SELECT id,version_id,seq,type,payload,created_at FROM events WHERE id=?")) {
            ps.setString(1, id);
            var rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new EventRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                    rs.getString(4), rs.getString(5), rs.getString(6));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returning to an old event restores the dependency version and the visible
     * conclusions of that moment; it never overwrites history with fresh results.
     */
    public VersionRow restoreToEvent(String eventId) {
        EventRow e = getEvent(eventId);
        if (e == null) throw new IllegalArgumentException("unknown event " + eventId);
        exec("INSERT INTO current_pointer(name,version_id) VALUES('main',?) " +
                "ON CONFLICT(name) DO UPDATE SET version_id=excluded.version_id", e.versionId());
        return getVersion(e.versionId());
    }

    // --------------------------------------------------------------- candidates

    public void addCandidate(String decisionId, String versionId, boolean chosen) {
        exec("INSERT INTO candidates(decision_id,version_id,chosen) VALUES(?,?,?) " +
                "ON CONFLICT(decision_id,version_id) DO UPDATE SET chosen=excluded.chosen",
                decisionId, versionId, chosen ? 1 : 0);
    }

    public List<Map<String, Object>> listCandidates(String decisionId) {
        return queryMaps("SELECT * FROM candidates WHERE decision_id = ?"
                .replace("?", "'" + decisionId.replace("'", "") + "'"));
    }

    /** Choosing a branch records the choice but keeps every unchosen candidate. */
    public void chooseCandidate(String decisionId, String chosenVersionId) {
        List<Map<String, Object>> all = listCandidates(decisionId);
        for (Map<String, Object> row : all) {
            String vid = (String) row.get("version_id");
            addCandidate(decisionId, vid, vid.equals(chosenVersionId));
        }
    }

    // ------------------------------------------------------------ export/import

    private static final List<String> TABLES =
            List.of("meta", "versions", "current_pointer", "events", "conflicts", "candidates");

    public String exportJson() {
        ObjectNode root = M.createObjectNode();
        root.put("format", "ext4-journal-lab-export");
        root.put("schemaVersion", Database.SCHEMA_VERSION);
        ObjectNode tables = root.putObject("tables");
        for (String t : TABLES) {
            var arr = tables.putArray(t);
            for (Map<String, Object> row : queryMaps("SELECT * FROM " + t)) {
                arr.add(M.valueToTree(row));
            }
        }
        return toJson(root);
    }

    /** Imports into an empty database, preserving identities, order and lineage. */
    public void importJson(String json) {
        try {
            JsonNode root = M.readTree(json);
            JsonNode tables = root.get("tables");
            db.conn().setAutoCommit(false);
            for (String t : TABLES) {
                JsonNode rows = tables.get(t);
                if (rows == null) continue;
                for (JsonNode row : rows) {
                    insertRow(t, row);
                }
            }
            db.conn().commit();
        } catch (Exception e) {
            throw new RuntimeException("import failed", e);
        } finally {
            quietAutoCommit();
        }
    }

    private void insertRow(String table, JsonNode row) throws SQLException {
        List<String> cols = new ArrayList<>();
        row.fieldNames().forEachRemaining(cols::add);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append('(');
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) { sql.append(','); marks.append(','); }
            sql.append(cols.get(i));
            marks.append('?');
        }
        sql.append(") VALUES(").append(marks).append(')');
        try (var ps = db.conn().prepareStatement(sql.toString())) {
            for (int i = 0; i < cols.size(); i++) {
                JsonNode v = row.get(cols.get(i));
                if (v == null || v.isNull()) ps.setObject(i + 1, null);
                else if (v.isIntegralNumber()) ps.setLong(i + 1, v.longValue());
                else ps.setString(i + 1, v.asText());
            }
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ helpers

    public boolean isEmpty() {
        return listVersions().isEmpty();
    }

    private long nextSeq(String counter) {
        long v = db.metaInt("seq_" + counter, 0) + 1;
        db.setMeta("seq_" + counter, String.valueOf(v));
        return v;
    }

    private void exec(String sql, Object... args) {
        try (var ps = db.conn().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Map<String, Object>> queryMaps(String sql) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var st = db.conn().createStatement(); var rs = st.executeQuery(sql)) {
            var md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnName(i), rs.getObject(i));
                }
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void quietAutoCommit() {
        try { db.conn().setAutoCommit(true); } catch (SQLException ignored) {}
    }

    static String toJson(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static JsonNode readJson(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
