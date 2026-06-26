package com.migration.platform.connection;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.TableInfo;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * Introspects a source/target database so the UI can drive table selection and mapping (issue #30).
 * Uses portable JDBC {@link DatabaseMetaData} for tables/columns/PKs; CDC-enabled detection is
 * SQL Server specific (cdc.change_tables).
 */
@Service
public class SchemaDiscoveryService {

    private final ConnectionRepository repo;
    private final CryptoService crypto;
    private final JdbcSupport jdbc;

    public SchemaDiscoveryService(ConnectionRepository repo, CryptoService crypto, JdbcSupport jdbc) {
        this.repo = repo;
        this.crypto = crypto;
        this.jdbc = jdbc;
    }

    public List<TableInfo> listTables(UUID connectionId, String schemaFilter) {
        DbConnection c = find(connectionId);
        String schema = effectiveSchema(c, schemaFilter);
        try (Connection conn = open(c)) {
            String catalog = conn.getCatalog();
            DatabaseMetaData md = conn.getMetaData();
            Set<String> cdc = c.getDbType() == DbType.SQLSERVER ? cdcEnabledTables(conn) : Set.of();

            List<TableInfo> out = new ArrayList<>();
            try (ResultSet rs = md.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String sch = rs.getString("TABLE_SCHEM");
                    String tbl = rs.getString("TABLE_NAME");
                    out.add(new TableInfo(sch, tbl, hasPrimaryKey(md, catalog, sch, tbl),
                            cdc.contains((sch + "." + tbl).toLowerCase())));
                }
            }
            out.sort(Comparator.comparing(TableInfo::tableName, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (SQLException e) {
            throw new IllegalArgumentException("Schema discovery failed: " + e.getMessage());
        }
    }

    public List<ColumnInfo> listColumns(UUID connectionId, String schema, String table) {
        DbConnection c = find(connectionId);
        try (Connection conn = open(c)) {
            String catalog = conn.getCatalog();
            DatabaseMetaData md = conn.getMetaData();
            Set<String> pks = primaryKeyColumns(md, catalog, schema, table);

            List<ColumnInfo> out = new ArrayList<>();
            try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    out.add(new ColumnInfo(
                            name,
                            rs.getString("TYPE_NAME"),
                            rs.getInt("COLUMN_SIZE"),
                            rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                            pks.contains(name)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalArgumentException("Column discovery failed: " + e.getMessage());
        }
    }

    private Connection open(DbConnection c) throws SQLException {
        return jdbc.open(c, crypto.decrypt(c.getPasswordEnc()));
    }

    private String effectiveSchema(DbConnection c, String schemaFilter) {
        if (schemaFilter != null && !schemaFilter.isBlank()) return schemaFilter;
        return c.getDbType() == DbType.SQLSERVER ? "dbo" : "public";
    }

    private boolean hasPrimaryKey(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            return rs.next();
        }
    }

    private Set<String> primaryKeyColumns(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        Set<String> pks = new HashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
        }
        return pks;
    }

    /** SQL Server: tables tracked by CDC. Returns "schema.table" (lowercased). Empty if CDC absent. */
    private Set<String> cdcEnabledTables(Connection conn) {
        String sql = """
                SELECT s.name AS schema_name, t.name AS table_name
                FROM cdc.change_tables ct
                JOIN sys.tables t ON ct.source_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                """;
        Set<String> set = new HashSet<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                set.add((rs.getString("schema_name") + "." + rs.getString("table_name")).toLowerCase());
            }
        } catch (SQLException ignored) {
            // CDC schema not present / not permitted — treat as none enabled.
        }
        return set;
    }

    private DbConnection find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Connection " + id + " not found"));
    }
}
