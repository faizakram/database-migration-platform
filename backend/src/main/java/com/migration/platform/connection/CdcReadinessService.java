package com.migration.platform.connection;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-engine CDC prerequisite checks (#80). Each source engine captures change data differently, so
 * "is this database ready for CDC?" means different things. Returns actionable findings with
 * remediation hints rather than a connector failing cryptically at deploy time.
 */
@Service
public class CdcReadinessService {

    private final ConnectionRepository repo;
    private final CryptoService crypto;
    private final JdbcSupport jdbc;

    public CdcReadinessService(ConnectionRepository repo, CryptoService crypto, JdbcSupport jdbc) {
        this.repo = repo;
        this.crypto = crypto;
        this.jdbc = jdbc;
    }

    public record Check(String name, boolean ok, String detail, String remediation) {}

    public record Readiness(DbType engine, String cdcStyle, boolean ready, List<Check> checks) {}

    public Readiness check(UUID connectionId) {
        DbConnection c = repo.findById(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection " + connectionId + " not found"));
        var style = EngineCatalog.spec(c.getDbType()).cdcStyle();
        List<Check> checks = new ArrayList<>();
        if (c.getDbType() == DbType.MONGODB) {
            // MongoDB isn't reached over JDBC; readiness is verified out-of-band (#100).
            checks.add(new Check("change streams enabled", true,
                    "MongoDB CDC uses change streams (requires a replica set / sharded cluster).",
                    "Run MongoDB as a replica set; grant the connector read + changeStream privileges."));
            return new Readiness(c.getDbType(), style.name(), true, checks);
        }
        try (Connection conn = jdbc.open(c, crypto.decrypt(c.getPasswordEnc()))) {
            switch (c.getDbType()) {
                case SQLSERVER -> sqlServer(conn, checks);
                case MYSQL -> mysql(conn, checks);
                case POSTGRESQL -> postgres(conn, checks);
                case ORACLE -> oracle(conn, checks);
                case DB2 -> db2(checks);
                case MONGODB -> { /* handled above */ }
            }
        } catch (SQLException e) {
            checks.add(new Check("connect", false, "Could not connect: " + e.getMessage(),
                    "Verify host/port/credentials and network access from the platform."));
        }
        boolean ready = checks.stream().allMatch(Check::ok);
        return new Readiness(c.getDbType(), style.name(), ready, checks);
    }

    /**
     * SQL Server only: of {@code selectedTables}, which lack a CDC capture instance. The Debezium
     * SQL Server connector derives its capture set from CDC capture instances and intersects that with
     * table.include.list — a selected table without table-level CDC is invisible to the connector and
     * is skipped entirely, even by the initial snapshot (full load). The connector still reports
     * RUNNING and silently delivers nothing for it, so nothing is created on the target (#191).
     *
     * <p>Returns empty for other engines (their snapshot reads included tables directly, so per-table
     * CDC isn't a precondition for the full load) and on any lookup failure (don't block on a transient
     * error — the database-level check and the connector still guard the real cases). Entries are
     * returned in the caller's original "schema.table" form.
     */
    public List<String> tablesMissingCdc(UUID connectionId, List<String> selectedTables) {
        DbConnection c = repo.findById(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection " + connectionId + " not found"));
        if (c.getDbType() != DbType.SQLSERVER || selectedTables == null || selectedTables.isEmpty()) {
            return List.of();
        }
        Set<String> enabled;
        try (Connection conn = jdbc.open(c, crypto.decrypt(c.getPasswordEnc()))) {
            enabled = cdcEnabledTables(conn);
        } catch (SQLException e) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String t : selectedTables) {
            String key = (t.contains(".") ? t : "dbo." + t).toLowerCase();
            if (!enabled.contains(key)) missing.add(t);
        }
        return missing;
    }

    /** SQL Server: "schema.table" (lowercased) for every table with a CDC capture instance. */
    private Set<String> cdcEnabledTables(Connection conn) throws SQLException {
        Set<String> set = new HashSet<>();
        String sql = """
                SELECT s.name AS schema_name, t.name AS table_name
                FROM cdc.change_tables ct
                JOIN sys.tables t ON ct.source_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                set.add((rs.getString("schema_name") + "." + rs.getString("table_name")).toLowerCase());
            }
        }
        return set;
    }

    private void sqlServer(Connection conn, List<Check> checks) throws SQLException {
        boolean dbCdc = scalarBool(conn,
                "SELECT is_cdc_enabled FROM sys.databases WHERE name = DB_NAME()");
        checks.add(new Check("database CDC enabled", dbCdc,
                dbCdc ? "sys.databases.is_cdc_enabled = 1" : "CDC is not enabled on this database",
                "Run EXEC sys.sp_cdc_enable_db as a DBA, then enable capture per table."));
        // Azure SQL Database (EngineEdition = 5) has no SQL Server Agent — CDC capture/cleanup runs on a
        // built-in scheduler — and sys.dm_server_services isn't queryable there. Only the Agent check is
        // Azure-specific; the database-level CDC requirement above is identical across editions.
        int edition = parseIntSafe(scalar(conn, "SELECT CAST(SERVERPROPERTY('EngineEdition') AS INT)"));
        if (edition == 5) {
            checks.add(new Check("CDC scheduler", true,
                    "Azure SQL Database runs CDC capture/cleanup on a built-in scheduler (no SQL Server Agent).",
                    "Ensure the database service tier supports CDC (S3 / 100 DTU or higher)."));
            return;
        }
        boolean agent = scalarBool(conn,
                "SELECT CASE WHEN EXISTS (SELECT 1 FROM sys.dm_server_services WHERE servicename LIKE 'SQL Server Agent%' AND status = 4) THEN 1 ELSE 0 END");
        checks.add(new Check("SQL Server Agent running", agent,
                agent ? "Agent service is running" : "SQL Server Agent appears stopped",
                "CDC capture/cleanup jobs require SQL Server Agent. Start the Agent service."));
    }

    private void mysql(Connection conn, List<Check> checks) throws SQLException {
        String logBin = scalar(conn, "SELECT @@log_bin");
        boolean binOn = "1".equals(logBin) || "ON".equalsIgnoreCase(logBin);
        checks.add(new Check("binary logging", binOn, "@@log_bin = " + logBin,
                "Set log_bin=ON (server restart) to enable binlog-based CDC."));
        String fmt = scalar(conn, "SELECT @@binlog_format");
        checks.add(new Check("binlog_format = ROW", "ROW".equalsIgnoreCase(fmt), "@@binlog_format = " + fmt,
                "Set binlog_format=ROW so row-level changes are captured."));
        String img = scalar(conn, "SELECT @@binlog_row_image");
        checks.add(new Check("binlog_row_image = FULL", "FULL".equalsIgnoreCase(img), "@@binlog_row_image = " + img,
                "Set binlog_row_image=FULL so before/after images are complete."));
    }

    private void postgres(Connection conn, List<Check> checks) throws SQLException {
        String wal = scalar(conn, "SHOW wal_level");
        checks.add(new Check("wal_level = logical", "logical".equalsIgnoreCase(wal), "wal_level = " + wal,
                "Set wal_level=logical (server restart) for logical decoding."));
        String slots = scalar(conn, "SHOW max_replication_slots");
        boolean hasSlots = parseIntSafe(slots) > 0;
        checks.add(new Check("replication slots available", hasSlots, "max_replication_slots = " + slots,
                "Set max_replication_slots >= 1 so Debezium can create a slot."));
    }

    private void oracle(Connection conn, List<Check> checks) throws SQLException {
        String logmode = scalar(conn, "SELECT log_mode FROM v$database");
        checks.add(new Check("ARCHIVELOG mode", "ARCHIVELOG".equalsIgnoreCase(logmode), "log_mode = " + logmode,
                "Enable ARCHIVELOG mode; LogMiner-based CDC requires it."));
        String suppl = scalar(conn, "SELECT supplemental_log_data_min FROM v$database");
        checks.add(new Check("minimal supplemental logging", "YES".equalsIgnoreCase(suppl),
                "supplemental_log_data_min = " + suppl,
                "ALTER DATABASE ADD SUPPLEMENTAL LOG DATA; (plus per-table for all columns)."));
    }

    private void db2(List<Check> checks) {
        checks.add(new Check("ASN capture configured", true,
                "Db2 readiness is verified out-of-band (ASN capture must be set up by a DBA).",
                "Run the Db2 ASN setup (sysproc.asncap) and enable capture for the tables."));
    }

    private boolean scalarBool(Connection conn, String sql) throws SQLException {
        String v = scalar(conn, sql);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int parseIntSafe(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
