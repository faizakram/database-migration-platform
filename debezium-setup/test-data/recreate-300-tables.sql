-- Recreate PascalShop as a large source: 300 tables (T001..T300), 10,000 rows each.
-- CDC enabled at DB level + on T001..T150 (T151..T300 left without table-level CDC) so the
-- UI's "select all CDC / non-CDC / both" can be exercised. Run with: sqlcmd -b -C.
SET NOCOUNT ON;

IF DB_ID('PascalShop') IS NOT NULL
BEGIN
    ALTER DATABASE PascalShop SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE PascalShop;
END;
GO
CREATE DATABASE PascalShop;
GO
USE PascalShop;
GO
EXEC sys.sp_cdc_enable_db;
GO

-- Reusable numbers table (1..10000) for fast set-based inserts.
;WITH n AS (
    SELECT TOP (10000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
    FROM sys.all_objects a CROSS JOIN sys.all_objects b
)
SELECT n INTO dbo._Numbers FROM n;
GO

DECLARE @i INT = 1, @t SYSNAME, @sql NVARCHAR(MAX);
WHILE @i <= 300
BEGIN
    SET @t = 'T' + RIGHT('000' + CAST(@i AS VARCHAR(3)), 3);

    SET @sql = 'CREATE TABLE dbo.' + QUOTENAME(@t) + ' (
        Id INT NOT NULL PRIMARY KEY,
        Code NVARCHAR(20) NOT NULL,
        Name NVARCHAR(100) NOT NULL,
        Amount DECIMAL(12,2) NULL,
        Qty INT NULL,
        IsActive BIT NOT NULL,
        CreatedAt DATETIME2 NOT NULL );';
    EXEC sys.sp_executesql @sql;

    SET @sql = 'INSERT INTO dbo.' + QUOTENAME(@t) + ' (Id, Code, Name, Amount, Qty, IsActive, CreatedAt)
        SELECT n, CONCAT(''C'', n), CONCAT(''Name '', n), (n % 1000) * 1.5, n % 50, n % 2, SYSDATETIME()
        FROM dbo._Numbers;';
    EXEC sys.sp_executesql @sql;

    -- Enable table-level CDC on the first half only → a clean 150/150 CDC vs non-CDC mix.
    IF @i <= 150
        EXEC sys.sp_cdc_enable_table @source_schema = 'dbo', @source_name = @t, @role_name = NULL;

    IF @i % 50 = 0 RAISERROR('  ... %d tables done', 0, 1, @i) WITH NOWAIT;
    SET @i += 1;
END;
GO

DROP TABLE dbo._Numbers;
GO

SELECT 'tables' AS metric, COUNT(*) AS value FROM sys.tables WHERE is_ms_shipped = 0 AND name LIKE 'T[0-9][0-9][0-9]'
UNION ALL
SELECT 'cdc_enabled', COUNT(*) FROM sys.tables WHERE is_tracked_by_cdc = 1
UNION ALL
SELECT 'rows_T001', COUNT(*) FROM dbo.T001
UNION ALL
SELECT 'rows_T300', COUNT(*) FROM dbo.T300;
GO
