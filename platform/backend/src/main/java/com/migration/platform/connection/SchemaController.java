package com.migration.platform.connection;

import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.TableInfo;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Live schema discovery for a stored connection (issue #30). */
@RestController
@RequestMapping("/api/v1/connections/{id}/schema")
public class SchemaController {

    private final SchemaDiscoveryService discovery;

    public SchemaController(SchemaDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/tables")
    public List<TableInfo> tables(@PathVariable UUID id,
                                  @RequestParam(required = false) String schema) {
        return discovery.listTables(id, schema);
    }

    @GetMapping("/columns")
    public List<ColumnInfo> columns(@PathVariable UUID id,
                                    @RequestParam String schema,
                                    @RequestParam String table) {
        return discovery.listColumns(id, schema, table);
    }
}
