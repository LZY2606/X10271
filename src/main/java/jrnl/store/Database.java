package jrnl.store;

import jrnl.domain.BlockSize;
import jrnl.domain.ChecksumVersion;
import jrnl.domain.CrashPoint;
import jrnl.domain.RawBlock;
import jrnl.domain.Snapshot;
import jrnl.domain.Snapshot.RingStart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SQLite-backed event/version store.
 *
 * Every derivation is an independent immutable version; the current pointer in
 * cases.head_event_id is switched only after the whole new version commits.
 * Version insert and head switch happen in one transaction, so a process killed
 * mid-write leaves either the old version or the complete new one.
 */
public final class Database implements AutoCloseable {

    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            Migrations.migrate(c);
            return new Database(c);
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    /** Test hook: in-memory database. */
    public static Database memory() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            Migrations.migrate(c);
            return new Database(c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> listCases() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT case_id FROM cases ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public String headEventId(String caseId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT head_event_id FROM cases WHERE case_id=?")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean caseExists(String caseId) {
        return headEventId(caseId) != null;
    }

    /** First event of a case: IMPORT with the full source snapshot. */
    public EventRow seedCase(String title, SnapshotInput input) {
        return commitVersion(title, input, null, null, null, null, null, null, false);
    }

    /**
     * Append a new version.
     *
     * @param baseEventId optimistic-lock base: must equal the current head
     * @param input       new source snapshot (full replacement), or null for a
     *                    decision-only version
     * @param kind        REVISE_PARAMS / REVISE_SOURCE / DECISION
     */
    public EventRow appendVersion(String caseId, String baseEventId, String kind,
                                  SnapshotInput input, String decisionJson,
                                  String candidateOfEventId, String title, String rationale) {
        String head = headEventId(caseId);
        if (head == null) {
            throw new IllegalStateException("unknown case " + caseId);
        }
        if (baseEventId != null && !baseEventId.equals(head)) {
            throw new ConflictException(caseId, baseEventId, head);
        }
        return commitVersion(null, input, caseId, kind, baseEventId,
                decisionJson, candidateOfEventId, title, rationale, true);
    }

    private EventRow commitVersion(String title, SnapshotInput input, String caseIdArg,
                                   String kind, String baseEventId, String decisionJson,
                                   String candidateOfEventId, String titleArg, boolean chosen) {
        throw new UnsupportedOperationException();
    }

    private EventRow commitVersion(String newCaseTitle, SnapshotInput input,
                                   String caseIdArg, String kind, String baseEventId,
                                   String decisionJson, String candidateOfEventId,
                                   String titleArg, String rationale, boolean chosen) {
        boolean isSeed = newCaseTitle != null;
        String caseId = isSeed ? input.caseId() : caseIdArg;
        BlockSize bs = BlockSize.fromRaw(input.blockSizeRaw());
        ChecksumVersion cv = input.checksumVersion();
        String versionId = "ver-" + UUID.randomUUID();
        String parentEventId = baseEventId;
        String parentVersionId = versionIdOfEvent(baseEventId);

        try {
            conn.setAutoCommit(false);
            if (isSeed) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cases(case_id, title, head_event_id) VALUES(?,?,NULL)")) {
                    ps.setString(1, caseId);
                    ps.setString(2, newCaseTitle);
                    ps.executeUpdate();
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO case_versions(version_id, case_id, parent_version_id, label,
                      status, block_size_raw, block_size_bytes, block_size_diag,
                      checksum_raw, checksum_version, ring_start_image, ring_start_raw, decision_json)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                String label = kind == null ? "import" : kind.toLowerCase();
                ps.setString(1, versionId);
                ps.setString(2, caseId);
                ps.setString(3, parentVersionId);
                ps.setString(4, label);
                ps.setString(5, "complete");
                ps.setString(6, input.blockSizeRaw());
                if (bs.bytes() == null) {
                    ps.setNull(7, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(7, bs.bytes());
                }
                ps.setString(8, bs.diagnostic());
                ps.setString(9, input.checksumRaw());
                ps.setString(10, cv.name());
                ps.setInt(11, input.ringStartImage());
                ps.setString(12, input.ringStartRaw());
                ps.setString(13, decisionJson);
                ps.executeUpdate();
            }

            List<BlockInput> blocks = input.blocks() == null ? List.of() : input.blocks();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO raw_blocks(version_id, image_index, hex_bytes, source_ref, kind, note)
                    VALUES(?,?,?,?,?,?)""")) {
                for (BlockInput b : blocks) {
                    ps.setString(1, versionId);
                    ps.setInt(2, b.imageIndex());
                    ps.setString(3, b.hex());
                    ps.setString(4, b.sourceRef());
                    ps.setString(5, b.kind());
                    ps.setString(6, b.note());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            List<CrashInput> crashes = input.crashPoints() == null ? List.of() : input.crashPoints();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO crash_points(version_id, image_index, raw_repr, diagnostic)
                    VALUES(?,?,?,?)""")) {
                for (CrashInput cp : crashes) {
                    ps.setString(1, versionId);
                    ps.setInt(2, cp.imageIndex());
                    ps.setString(3, cp.rawRepr());
                    ps.setString(4, cp.diagnostic());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            long seq = nextEventSeq(caseId);
            String eventKind = kind == null ? "IMPORT" : kind;
            String eventId = "evt:" + caseId + ":" + Long.toUnsignedString(seq);
            String eventTitle = isSeed ? "IMPORT · " + newCaseTitle : titleArg;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO events(event_id, case_id, seq, kind, version_id, parent_event_id,
                      base_event_id, candidate_of_event_id, chosen, title, rationale, payload_json)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                ps.setString(1, eventId);
                ps.setString(2, caseId);
                ps.setLong(3, seq);
                ps.setString(4, eventKind);
                ps.setString(5, versionId);
                ps.setString(6, parentEventId);
                ps.setString(7, baseEventId);
                ps.setString(8, candidateOfEventId);
                ps.setInt(9, chosen ? 1 : 0);
                ps.setString(10, eventTitle);
                ps.setString(11, rationale);
                ps.setString(12, decisionJson);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE cases SET head_event_id=? WHERE case_id=?")) {
                ps.setString(1, eventId);
                ps.setString(2, caseId);
                ps.executeUpdate();
            }

            conn.commit();
            return getEvent(eventId);
        } catch (SQLException e) {
            rollback();
            throw new RuntimeException(e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    private long nextEventSeq(String caseId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(seq),0)+1 FROM events WHERE case_id=?")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 1;
            }
        }
    }

    private String versionIdOfEvent(String eventId) {
        if (eventId == null) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT version_id FROM events WHERE event_id=?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void rollback() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    public EventRow getEvent(String eventId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_id, case_id, seq, kind, version_id, parent_event_id, base_event_id,"
                        + " candidate_of_event_id, chosen, title, rationale, payload_json, created_at"
                        + " FROM events WHERE event_id=?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new EventRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getInt(9) != 0, rs.getString(10),
                        rs.getString(11), rs.getString(12), rs.getString(13));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<EventRow> events(String caseId) {
        List<EventRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_id, case_id, seq, kind, version_id, parent_event_id, base_event_id,"
                        + " candidate_of_event_id, chosen, title, rationale, payload_json, created_at"
                        + " FROM events WHERE case_id=? ORDER BY seq")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EventRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), rs.getInt(9) != 0, rs.getString(10),
                            rs.getString(11), rs.getString(12), rs.getString(13)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Snapshot snapshotAt(String eventId) {
        EventRow ev = Objects.requireNonNull(getEvent(eventId), "no event " + eventId);
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT v.case_id, v.block_size_raw, v.checksum_raw, v.ring_start_image,
                       v.ring_start_raw, b.image_index, b.hex_bytes, b.source_ref,
                       c.image_index, c.raw_repr, c.diagnostic
                FROM case_versions v
                LEFT JOIN raw_blocks b ON b.version_id = v.version_id
                LEFT JOIN crash_points c ON c.version_id = v.version_id
                WHERE v.version_id=?
                ORDER BY b.image_index, c.image_index""")) {
            ps.setString(1, ev.versionId());
            try (ResultSet rs = ps.executeQuery()) {
                List<RawBlock> blocks = new ArrayList<>();
                List<CrashPoint> crashes = new ArrayList<>();
                String caseId = null;
                String bsRaw = null;
                String csRaw = null;
                int ringStart = 0;
                String ringStartRaw = null;
                while (rs.next()) {
                    if (caseId == null) {
                        caseId = rs.getString(1);
                        bsRaw = rs.getString(2);
                        csRaw = rs.getString(3);
                        ringStart = rs.getInt(4);
                        ringStartRaw = rs.getString(5);
                    }
                    int bi = rs.getInt(6);
                    if (!rs.wasNull()) {
                        blocks.add(new RawBlock(bi, rs.getString(7), rs.getString(8)));
                    }
                    int ci = rs.getInt(9);
                    if (!rs.wasNull()) {
                        crashes.add(new CrashPoint(ci, rs.getString(10), rs.getString(11)));
                    }
                }
                return new Snapshot(caseId, blocks, new RingStart(ringStart, ringStartRaw),
                        BlockSize.fromRaw(bsRaw), ChecksumVersion.fromRaw(csRaw), csRaw,
                        dedupe(crashes), List.of());
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<CrashPoint> dedupe(List<CrashPoint> in) {
        List<CrashPoint> out = new ArrayList<>();
        for (CrashPoint cp : in) {
            if (out.stream().noneMatch(o -> o.stopAfterImageIndex() == cp.stopAfterImageIndex())) {
                out.add(cp);
            }
        }
        return out;
    }

    public void putDerived(String eventId, String nodeKind, String coord,
                           String stableId, String jsonValue, String inputsHash) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO derived_cache(event_id, node_kind, coord, stable_id, json_value, inputs_hash)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(event_id, node_kind, coord)
                DO UPDATE SET stable_id=excluded.stable_id, json_value=excluded.json_value,
                              inputs_hash=excluded.inputs_hash""")) {
            ps.setString(1, eventId);
            ps.setString(2, nodeKind);
            ps.setString(3, coord);
            ps.setString(4, stableId);
            ps.setString(5, jsonValue);
            ps.setString(6, inputsHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public String getDerivedJson(String eventId, String nodeKind, String coord) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT json_value FROM derived_cache WHERE event_id=? AND node_kind=? AND coord=?")) {
            ps.setString(1, eventId);
            ps.setString(2, nodeKind);
            ps.setString(3, coord);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
