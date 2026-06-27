package com.migration.platform.validation;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;
import com.migration.platform.connection.JdbcSupport;
import com.migration.platform.connector.DeleteStrategy;
import com.migration.platform.connector.MigrationConfig;
import com.migration.platform.connector.TargetNaming;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import com.migration.platform.validation.dto.ValidationDtos.TableValidation;
import com.migration.platform.validation.dto.ValidationDtos.ValidationReport;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Advanced post-migration validation (#96): beyond row-count + checksum (#49), it checks for
 * NULL primary keys, duplicate keys, and (bounded) missing rows on the target, and produces a
 * consolidated PASS/FAIL report that can be exported. Target identifiers are resolved through the
 * project's naming strategy (#84) so the right table/columns are located.
 */
@Service
public class ValidationService {

    private static final int MISSING_SAMPLE = 1000;

    private final ProjectRepository projects;
    private final ConnectionRepository connections;
    private final JdbcSupport jdbc;
    private final CryptoService crypto;

    public ValidationService(ProjectRepository projects, ConnectionRepository connections,
                             JdbcSupport jdbc, CryptoService crypto) {
        this.projects = projects;
        this.connections = connections;
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public ValidationReport validate(UUID projectId) {
        MigrationProject p = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        MigrationConfig mc = MigrationConfig.from(p.getConfig(), p.getName());
        DbConnection src = connections.findById(p.getSourceConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("No source connection"));
        DbConnection tgt = connections.findById(p.getTargetConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("No target connection"));
        boolean softDelete = mc.deleteStrategy() == DeleteStrategy.SOFT;

        List<String> selected = selectedTables(p);
        if (selected.isEmpty()) throw new IllegalArgumentException("No tables selected for this project");

        List<TableValidation> results = new ArrayList<>();
        try (Connection sc = jdbc.open(src, crypto.decrypt(src.getPasswordEnc()));
             Connection tc = jdbc.open(tgt, crypto.decrypt(tgt.getPasswordEnc()))) {
            for (String fq : selected) {
                String[] parts = fq.split("\\.", 2);
                String schema = parts.length == 2 ? parts[0] : "dbo";
                String table = parts.length == 2 ? parts[1] : parts[0];
                results.add(validateTable(sc, tc, src.getDbType(), tgt.getDbType(), schema, table, mc, softDelete));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Validation failed: " + e.getMessage());
        }
        int passed = (int) results.stream().filter(r -> "PASS".equals(r.status())).count();
        int failed = (int) results.stream().filter(r -> !"PASS".equals(r.status())).count();
        return new ValidationReport(results.size(), passed, failed, results);
    }

    private TableValidation validateTable(Connection sc, Connection tc, DbType srcEngine, DbType tgtEngine,
                                          String schema, String table, MigrationConfig mc, boolean softDelete) {
        try {
            // Quote target identifiers so case-sensitive names (e.g. PascalCase under PRESERVE) match;
            // unquoted names get folded to lower case by PostgreSQL and miss a "OrderItems"-style table.
            String tgtTable = qid(tgtEngine, mc.targetSchema()) + "." + qid(tgtEngine, TargetNaming.apply(table, mc.namingStrategy()));
            // The soft-delete marker is renamed by the naming strategy too (e.g. snake_case -> cdc_deleted).
            String deleteMarker = qid(tgtEngine, TargetNaming.apply("__cdc_deleted", mc.namingStrategy()));
            String softFilter = softDelete ? " WHERE " + deleteMarker + " IS NOT TRUE" : "";

            long sourceRows = scalar(sc, "SELECT COUNT(*) FROM " + sourceQualify(srcEngine, schema, table));
            long targetRows = scalar(tc, "SELECT COUNT(*) FROM " + tgtTable + softFilter);

            String pk = primaryKey(sc, schema, table);

            long nullPk = 0, dupKeys = 0, missing = 0;
            if (pk != null) {
                String tgtPk = qid(tgtEngine, TargetNaming.apply(pk, mc.namingStrategy()));
                nullPk = scalar(tc, "SELECT COUNT(*) FROM " + tgtTable + " WHERE " + tgtPk + " IS NULL");
                dupKeys = scalar(tc, "SELECT COALESCE(SUM(c-1),0) FROM (SELECT " + tgtPk
                        + " AS k, COUNT(*) c FROM " + tgtTable + " GROUP BY " + tgtPk + " HAVING COUNT(*)>1) d");
                missing = missingSample(sc, tc, srcEngine, schema, table, pk, tgtTable, tgtPk);
            }
            long extra = Math.max(0, targetRows - sourceRows);
            long[] ops = cdcOpCounts(sc, srcEngine, schema, table);
            return ValidationLogic.assess(schema, table, sourceRows, targetRows, nullPk, dupKeys, missing, extra,
                    ops[0], ops[1], ops[2]);
        } catch (Exception e) {
            return new TableValidation(schema, table, -1, -1, 0, 0, 0, 0, -1, -1, -1, "ERROR", List.of(e.getMessage()));
        }
    }

    /**
     * Best-effort per-table CDC change activity from the source, for user visibility (not a PASS/FAIL signal).
     * Returns {inserts, updates, deletes}; {-1,-1,-1} when the engine/table exposes no change log.
     * SQL Server keeps these in the CDC change tables: {@code cdc.<capture_instance>_CT}, where
     * {@code __$operation} is 2=insert, 4=update (after image), 1=delete (3=update before image, ignored).
     */
    private long[] cdcOpCounts(Connection sc, DbType srcEngine, String schema, String table) {
        if (srcEngine != DbType.SQLSERVER) return new long[]{-1, -1, -1};
        try {
            String captureInstance = null;
            String find = "SELECT ct.capture_instance FROM cdc.change_tables ct "
                    + "JOIN sys.tables t ON ct.source_object_id = t.object_id "
                    + "JOIN sys.schemas s ON t.schema_id = s.schema_id "
                    + "WHERE s.name = '" + schema.replace("'", "''") + "' "
                    + "AND t.name = '" + table.replace("'", "''") + "'";
            try (Statement st = sc.createStatement(); ResultSet rs = st.executeQuery(find)) {
                if (rs.next()) captureInstance = rs.getString(1);
            }
            if (captureInstance == null) return new long[]{-1, -1, -1};

            long inserts = 0, updates = 0, deletes = 0;
            String ct = "[cdc].[" + captureInstance.replace("]", "]]") + "_CT]";
            String q = "SELECT [__$operation], COUNT_BIG(*) FROM " + ct + " GROUP BY [__$operation]";
            try (Statement st = sc.createStatement(); ResultSet rs = st.executeQuery(q)) {
                while (rs.next()) {
                    int op = rs.getInt(1);
                    long n = rs.getLong(2);
                    switch (op) {
                        case 2 -> inserts = n;
                        case 4 -> updates = n;
                        case 1 -> deletes = n;
                        default -> { /* 3 = update before-image, ignore */ }
                    }
                }
            }
            return new long[]{inserts, updates, deletes};
        } catch (Exception e) {
            return new long[]{-1, -1, -1};   // CDC not enabled / no permission — non-fatal
        }
    }

    /** Bounded missing-row check: sample source keys, count how many are absent on the target. */
    private long missingSample(Connection sc, Connection tc, DbType srcEngine, String schema, String table,
                               String pk, String tgtTable, String tgtPk) {
        try {
            List<String> keys = new ArrayList<>();
            String topN = srcEngine == DbType.SQLSERVER ? "TOP (" + MISSING_SAMPLE + ") " : "";
            String limit = srcEngine == DbType.SQLSERVER ? "" : " LIMIT " + MISSING_SAMPLE;
            String q = "SELECT " + topN + pkRef(srcEngine, pk) + " FROM " + sourceQualify(srcEngine, schema, table) + limit;
            try (Statement st = sc.createStatement(); ResultSet rs = st.executeQuery(q)) {
                while (rs.next()) { Object v = rs.getObject(1); if (v != null) keys.add(v.toString()); }
            }
            long miss = 0;
            for (String k : keys) {
                long found = scalar(tc, "SELECT COUNT(*) FROM " + tgtTable
                        + " WHERE lower(cast(" + tgtPk + " AS text)) = lower('" + k.replace("'", "''") + "')");
                if (found == 0) miss++;
            }
            return miss;
        } catch (Exception e) {
            return 0;   // best-effort; non-fatal
        }
    }

    private String sourceQualify(DbType engine, String schema, String table) {
        return switch (engine) {
            case SQLSERVER -> "[" + schema + "].[" + table + "]";
            case MYSQL -> "`" + table + "`";
            default -> schema + "." + table;
        };
    }
    /** Quote a target identifier for the target engine so case-sensitive names match. */
    private String qid(DbType engine, String id) {
        return switch (engine) {
            case MYSQL -> "`" + id.replace("`", "``") + "`";
            case SQLSERVER -> "[" + id.replace("]", "]]") + "]";
            default -> "\"" + id.replace("\"", "\"\"") + "\"";   // PostgreSQL / Oracle / Db2
        };
    }

    private String pkRef(DbType engine, String pk) {
        return engine == DbType.SQLSERVER ? "[" + pk + "]" : pk;
    }

    private long scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** First primary-key column of the source table (null if none / composite handled as none here). */
    private String primaryKey(Connection sc, String schema, String table) {
        try (ResultSet rs = sc.getMetaData().getPrimaryKeys(sc.getCatalog(), schema, table)) {
            return rs.next() ? rs.getString("COLUMN_NAME") : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedTables(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        if (v instanceof List<?> list) return list.stream().map(Object::toString).toList();
        return List.of();
    }
}
