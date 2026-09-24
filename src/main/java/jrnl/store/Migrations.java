package jrnl.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * SQLite schema migrations. Old databases from schema v1 are migrated forward,
 * never discarded; stable identities are preserved across migration.
 */
public final class Migrations {

    public static final int CURRENT_VERSION = 2;

    private Migrations() {
    }

    public static void migrate(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int v = currentVersion(c);
        if (v == 0) {
            createV1(c);
            v = 1;
        }
        if (v < 2) {
            migrateV1ToV2(c);
            v = 2;
        }
        try (Statement st = c.createStatement()) {
            st.execute("DELETE FROM schema_version");
            st.execute("INSERT INTO schema_version(version) VALUES (" + CURRENT_VERSION + ")");
        }
    }

    private static int currentVersion(Connection c) throws Exception {
        try {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static void createV1(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE cases (
                      case_id TEXT PRIMARY KEY,
                      title TEXT NOT NULL,
                      head_event_id TEXT,
                      created_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )""");
            st.executeUpdate("""
                    CREATE TABLE case_versions (
                      version_id TEXT PRIMARY KEY,
                      case_id TEXT NOT NULL REFERENCES cases(case_id),
                      parent_version_id TEXT,
                      label TEXT NOT NULL,
                      status TEXT NOT NULL DEFAULT 'complete',
                      block_size_raw TEXT,
                      block_size_bytes INTEGER,
                      checksum_raw TEXT,
                      checksum_version TEXT,
                      ring_start_image INTEGER NOT NULL,
                      ring_start_raw TEXT,
                      created_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )""");
            st.executeUpdate("""
                    CREATE TABLE raw_blocks (
                      version_id TEXT NOT NULL REFERENCES case_versions(version_id),
                      image_index INTEGER NOT NULL,
                      hex_bytes TEXT NOT NULL,
                      source_ref TEXT,
                      kind TEXT,
                      note TEXT,
                      PRIMARY KEY (version_id, image_index)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE crash_points (
                      version_id TEXT NOT NULL REFERENCES case_versions(version_id),
                      image_index INTEGER NOT NULL,
                      raw_repr TEXT NOT NULL,
                      diagnostic TEXT,
                      PRIMARY KEY (version_id, image_index)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE events (
                      event_id TEXT PRIMARY KEY,
                      case_id TEXT NOT NULL REFERENCES cases(case_id),
                      seq INTEGER NOT NULL,
                      kind TEXT NOT NULL,
                      version_id TEXT REFERENCES case_versions(version_id),
                      parent_event_id TEXT,
                      base_event_id TEXT,
                      title TEXT NOT NULL,
                      payload_json TEXT,
                      created_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )""");
            st.executeUpdate("CREATE UNIQUE INDEX uq_events_seq ON events(case_id, seq)");
            st.executeUpdate("""
                    CREATE TABLE derived_cache (
                      event_id TEXT NOT NULL REFERENCES events(event_id),
                      node_kind TEXT NOT NULL,
                      coord TEXT NOT NULL,
                      stable_id TEXT NOT NULL,
                      json_value TEXT NOT NULL,
                      inputs_hash TEXT NOT NULL,
                      PRIMARY KEY (event_id, node_kind, coord)
                    )""");
        }
    }

    private static void migrateV1ToV2(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE case_versions ADD COLUMN block_size_diag TEXT");
            st.executeUpdate("ALTER TABLE case_versions ADD COLUMN decision_json TEXT");
            st.executeUpdate("ALTER TABLE events ADD COLUMN candidate_of_event_id TEXT");
            st.executeUpdate("ALTER TABLE events ADD COLUMN chosen INTEGER NOT NULL DEFAULT 1");
        }
    }
}
