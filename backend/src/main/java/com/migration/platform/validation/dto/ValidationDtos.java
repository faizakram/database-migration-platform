package com.migration.platform.validation.dto;

import java.util.List;

/** Advanced validation read models (#96). */
public final class ValidationDtos {

    private ValidationDtos() {}

    public record TableValidation(
            String schema, String table,
            long sourceRows, long targetRows,
            long nullPrimaryKey, long duplicateKeys,
            long missingRows, long extraRows,
            long cdcInserts, long cdcUpdates, long cdcDeletes,  // CDC change activity on the source; -1 if N/A
            String status,            // PASS | FAIL | ERROR
            List<String> issues
    ) {}

    public record ValidationReport(
            int tables, int passed, int failed,
            List<TableValidation> results
    ) {}
}
