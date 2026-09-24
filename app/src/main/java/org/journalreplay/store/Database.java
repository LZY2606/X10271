package org.journalreplay.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;

/**
 * SQLite storage with WAL journaling. Schema is versioned; opening an older
 * database migrates it forward without losing rows.
 *
 * <p>Schema v1 (legacy): meta, sources, versions, pointer, events.
 * Schema v2 (current): adds derivations, branches, candidates and the
 * sources.norm_json / sources.diag_json columns that keep normalised values
 * separate from raw representations.
 */
public final class Database implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(Path path) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        Database db = new Database(c);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        db.migrate();
        return db;
    }

    public static Database openInMemory() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = new Database(c);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
        }
        db.migrate();
        return db;
    }

    public Connection conn() {
        return conn;
    }

    public int schemaVersion() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private void migrate() throws SQLException {
        int v;
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")) {
            if (!rs.next()) {
                createV1();
                v = 1;
            } else {
                v = schemaVersion();
            }
        }
        if (v < 2) migrateV1toV2();
    }

    private void createV1() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("INSERT INTO meta(key,value) VALUES('schema_version','1')");
            s.execute("INSERT INTO meta(key,value) VALUES('version_counter','0')");
            s.execute("INSERT INTO meta(key,value) VALUES('event_counter','0')");
            s.execute("INSERT INTO meta(key,value) VALUES('branch_counter','0')");
            s.execute("CREATE TABLE sources("
                    + "id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,"
                    + "raw_repr TEXT, raw_bytes BLOB, unit TEXT, basis TEXT,"
                    + "created_seq INTEGER NOT NULL)");
            s.execute("CREATE TABLE versions("
                    + "id TEXT PRIMARY KEY, parent_id TEXT, label TEXT, actor TEXT,"
                    + "created_seq INTEGER NOT NULL)");
            s.execute("CREATE TABLE pointer(name TEXT PRIMARY KEY, version_id TEXT NOT NULL)");
            s.execute("CREATE TABLE events("
                    + "id TEXT PRIMARY KEY, version_id TEXT NOT NULL, seq INTEGER NOT NULL,"
                    + "kind TEXT NOT NULL, payload TEXT NOT NULL)");
        }
    }

    private void migrateV1toV2() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE sources ADD COLUMN norm_json TEXT");
            s.execute("ALTER TABLE sources ADD COLUMN diag_json TEXT");
            s.execute("CREATE TABLE IF NOT EXISTS derivations("
                    + "id TEXT PRIMARY KEY, version_id TEXT NOT NULL, rule_version TEXT NOT NULL,"
                    + "inputs TEXT NOT NULL, status TEXT NOT NULL, result TEXT, reason TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS branches("
                    + "id TEXT PRIMARY KEY, base_version TEXT NOT NULL, label TEXT NOT NULL,"
                    + "status TEXT NOT NULL, created_seq INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS candidates("
                    + "id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, payload TEXT NOT NULL,"
                    + "chosen INTEGER NOT NULL DEFAULT 0, kept INTEGER NOT NULL DEFAULT 1)");
            s.execute("UPDATE meta SET value='2' WHERE key='schema_version'");
        }
    }

    /** Next value of a named monotonic counter, persisted so ids survive restarts. */
    public synchronized long nextCounter(String key) throws SQLException {
        long v;
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='" + key + "'")) {
            v = rs.next() ? Long.parseLong(rs.getString(1)) : 0;
        }
        v++;
        try (var ps = conn.prepareStatement("UPDATE meta SET value=? WHERE key=?")) {
            ps.setString(1, Long.toString(v));
            ps.setString(2, key);
            ps.executeUpdate();
        }
        return v;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
