package com.migration.platform.validation;

import com.migration.platform.connection.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Composite/single primary-key expression building for validation (#180). */
class ValidationKeyExprTest {

    @Test
    void singleColumnIsJustItsTextCast() {
        assertThat(ValidationService.keyExpr(DbType.POSTGRESQL, List.of("\"id\"")))
                .isEqualTo("lower(cast(\"id\" AS text))");
    }

    @Test
    void compositeUsesMultiArgConcatWithSeparatorForConcatEngines() {
        // SQL Server / MySQL / PostgreSQL support multi-arg CONCAT.
        assertThat(ValidationService.keyExpr(DbType.POSTGRESQL, List.of("\"a\"", "\"b\"")))
                .isEqualTo("CONCAT(lower(cast(\"a\" AS text)), '~|~', lower(cast(\"b\" AS text)))");
        assertThat(ValidationService.keyExpr(DbType.SQLSERVER, List.of("[a]", "[b]")))
                .isEqualTo("CONCAT(lower(cast([a] AS varchar(4000))), '~|~', lower(cast([b] AS varchar(4000))))");
    }

    @Test
    void compositeUsesPipeConcatForOracleAndDb2() {
        assertThat(ValidationService.keyExpr(DbType.ORACLE, List.of("A", "B")))
                .isEqualTo("lower(cast(A AS varchar2(4000))) || '~|~' || lower(cast(B AS varchar2(4000)))");
    }
}
