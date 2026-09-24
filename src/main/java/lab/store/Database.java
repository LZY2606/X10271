package lab.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** SQLite handle with explicit, ordered migrations and crash recovery. */
public final class Database implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private static final List<String[]> MIGRATIONS = List.of(
            // v1: base schema
            new String[]{
                "CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)",
                "CREATE TABLE IF NOT EXISTS versions(" +
                    "id TEXT PRIMARY KEY, parent_id TEXT, kind TEXT, label TEXT," +
                    "status TEXT, payload TEXT, seq INTEGER, created_at TEXT)",
                "CREATE TABLE IF NOT EXISTS current_pointer(name TEXT PRIMARY KEY, version_id TEXT)",
                "CREATE TABLE IF NOT EXISTS events(" +
                    "id TEXT PRIMARY KEY, version_id TEXT, seq INTEGER, type TEXT," +
                    "payload TEXT, created_at TEXT)",
                "CREATE TABLE IF NOT EXISTS conflicts(" +
                    "id TEXT PRIMARY KEY, base_version_id TEXT, left_version_id TEXT," +
                    "right_version_id TEXT, status TEXT, resolved_version_id TEXT, created_at TEXT)"
            },
            // v2: decision candidates (unchosen branches are preserved)
            new String[]{
                "CREATE TABLE IF NOT EXISTS candidates(" +
                    "decision_id TEXT, version_id TEXT, chosen INTEGER," +
                    "PRIMARY KEY(decision_id, version_id))",
                "CREATE INDEX IF NOT EXISTS idx_events_seq ON events(seq)",
                "CREATE INDEX IF NOT EXISTS idx_versions_seq ON versions(seq)"
            }
    );

    private final Connection conn;

    private Database(Connection conn) { this.conn = conn; }

    public static Database open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            Database db = new Database(c);
            db.migrate();
            db.recover();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    public Connection conn() { return conn; }

    private void migrate() throws SQLException {
        int current = metaInt("schema_version", 0);
        for (int v = current + 1; v <= SCHEMA_VERSION; v++) {
            try (Statement s = conn.createStatement()) {
                for (String sql : MIGRATIONS.get(v - 1)) s.execute(sql);
            }
            setMeta("schema_version", String.valueOf(v));
        }
    }

    /**
     * After a crash mid-write, a version row may be left in WRITING state.
     * Recovery removes incomplete versions; the current pointer therefore
     * always references either the old version or a complete new one.
     */
    public void recover() {
        try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM versions WHERE status='WRITING'");
            s.execute("DELETE FROM current_pointer WHERE version_id NOT IN (SELECT id FROM versions)");
        } catch (SQLException e) {
            throw new RuntimeException("recovery failed", e);
        }
    }

    public int metaInt(String key, int dflt) {
        String v = meta(key);
        return v == null ? dflt : Integer.parseInt(v);
    }

    public String meta(String key) {
        try (var ps = conn.prepareStatement("SELECT v FROM meta WHERE k=?")) {
            ps.setString(1, key);
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    public void setMeta(String k, String v) {
        try (var ps = conn.prepareStatement("INSERT INTO meta(k,v) VALUES(?,?) " +
                "ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            ps.setString(1, k);
            ps.setString(2, v);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
