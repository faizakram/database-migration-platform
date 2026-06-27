-- Scale fixture for validation benchmarking (#154).
-- Seeds a large single-PK table into a dedicated SQL Server database and enables CDC on it,
-- so the integrity validation job can be timed against millions of rows.
--
-- Run with sqlcmd variables (defaults shown):
--   sqlcmd -S host,1433 -U sa -P "$SA" -v Db="PerfBench" Rows="5000000" -i seed-large-source.sql
--
-- Idempotent-ish: drops and recreates dbo.PerfOrders each run.

SET NOCOUNT ON;

-- Create the benchmark database if needed.
IF DB_ID('$(Db)') IS NULL
BEGIN
    DECLARE @create NVARCHAR(200) = N'CREATE DATABASE [$(Db)]';
    EXEC sp_executesql @create;
END
GO

USE [$(Db)];
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

-- Enable CDC at the database level (no-op if already on).
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = DB_NAME() AND is_cdc_enabled = 1)
    EXEC sys.sp_cdc_enable_db;
GO

IF OBJECT_ID('dbo.PerfOrders', 'U') IS NOT NULL
    DROP TABLE dbo.PerfOrders;
GO

CREATE TABLE dbo.PerfOrders (
    Id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    CustomerId  INT NOT NULL,
    Amount      DECIMAL(12,2) NOT NULL,
    Status      VARCHAR(16) NOT NULL,
    CreatedAt   DATETIME2 NOT NULL,
    Note        NVARCHAR(128) NULL
);
GO

-- Bulk-generate rows in batches using a cross-join tally (fast, set-based).
DECLARE @Rows BIGINT = CAST('$(Rows)' AS BIGINT);
DECLARE @Batch INT = 1000000;
DECLARE @done BIGINT = 0;

WHILE @done < @Rows
BEGIN
    DECLARE @take INT = CASE WHEN @Rows - @done > @Batch THEN @Batch ELSE CAST(@Rows - @done AS INT) END;

    ;WITH n AS (
        SELECT TOP (@take) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS rn
        FROM sys.all_columns a CROSS JOIN sys.all_columns b
    )
    INSERT INTO dbo.PerfOrders (CustomerId, Amount, Status, CreatedAt, Note)
    SELECT
        CAST((@done + rn) % 100000 AS INT),
        CAST((rn % 1000) * 1.5 AS DECIMAL(12,2)),
        CASE WHEN rn % 3 = 0 THEN 'PAID' WHEN rn % 3 = 1 THEN 'NEW' ELSE 'SHIPPED' END,
        SYSUTCDATETIME(),
        CONCAT('row-', @done + rn)
    FROM n;

    SET @done = @done + @take;
    RAISERROR('  seeded %I64d / %I64d rows', 0, 1, @done, @Rows) WITH NOWAIT;
END
GO

-- Enable CDC on the table (capture instance defaults to dbo_PerfOrders).
IF NOT EXISTS (
    SELECT 1 FROM cdc.change_tables ct
    JOIN sys.tables t ON ct.source_object_id = t.object_id
    JOIN sys.schemas s ON t.schema_id = s.schema_id
    WHERE s.name = 'dbo' AND t.name = 'PerfOrders'
)
    EXEC sys.sp_cdc_enable_table @source_schema = N'dbo', @source_name = N'PerfOrders', @role_name = NULL;
GO

SELECT COUNT_BIG(*) AS seeded_rows FROM dbo.PerfOrders;
GO
