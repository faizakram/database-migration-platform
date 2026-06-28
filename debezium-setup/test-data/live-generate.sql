-- Continuous live data generator for the recreated PascalShop source (300 generic tables T001..T300,
-- of which T001..T150 are CDC-enabled). Each tick (~1s) runs a mixed INSERT/UPDATE/DELETE workload
-- across a random sample of the CDC-enabled tables, so inserts/updates/deletes stream through CDC live
-- and show up in the integrity report.
--
-- Controlled by dbo._GenControl(Running): the loop runs while Running=1.
-- Stop it any time with:  UPDATE dbo._GenControl SET Running = 0 WHERE Id = 1;  (live-generate-stop.sh)
-- _GenControl is a helper table, not CDC-tracked, and is not part of any migration project's selection.
SET NOCOUNT ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;

IF OBJECT_ID('dbo._GenControl') IS NULL
    CREATE TABLE dbo._GenControl (Id INT PRIMARY KEY, Running BIT NOT NULL);
IF NOT EXISTS (SELECT 1 FROM dbo._GenControl WHERE Id = 1)
    INSERT INTO dbo._GenControl (Id, Running) VALUES (1, 1);
ELSE
    UPDATE dbo._GenControl SET Running = 1 WHERE Id = 1;

-- Only target CDC-enabled tables so the workload actually streams. Adjust the cap to taste.
DECLARE @cdcTableCount INT = 150;   -- T001..T150 are CDC-enabled
DECLARE @perTick INT = 8;           -- how many random tables to touch each second

DECLARE @tick INT = 0, @k INT, @n INT, @t SYSNAME, @sql NVARCHAR(MAX);

WHILE (SELECT Running FROM dbo._GenControl WHERE Id = 1) = 1
BEGIN
    SET @tick += 1;
    SET @k = 1;
    WHILE @k <= @perTick
    BEGIN
        SET @n = 1 + ABS(CHECKSUM(NEWID())) % @cdcTableCount;
        SET @t = 'T' + RIGHT('000' + CAST(@n AS VARCHAR(3)), 3);

        SET @sql = N'
            DECLARE @base INT = (SELECT ISNULL(MAX(Id), 0) FROM dbo.' + QUOTENAME(@t) + N');
            -- INSERT two fresh rows (Ids above the seeded 10k)
            INSERT INTO dbo.' + QUOTENAME(@t) + N' (Id, Code, Name, Amount, Qty, IsActive, CreatedAt)
            VALUES (@base + 1, CONCAT(''G'', @base + 1), ''gen-insert'', ABS(CHECKSUM(NEWID())) % 1000 * 1.0, ABS(CHECKSUM(NEWID())) % 50, 1, SYSDATETIME()),
                   (@base + 2, CONCAT(''G'', @base + 2), ''gen-insert'', ABS(CHECKSUM(NEWID())) % 1000 * 1.0, ABS(CHECKSUM(NEWID())) % 50, 0, SYSDATETIME());
            -- UPDATE two random rows
            UPDATE dbo.' + QUOTENAME(@t) + N' SET Amount = ISNULL(Amount, 0) + 1, Name = CONCAT(''gen-upd '', @base), CreatedAt = SYSDATETIME()
            WHERE Id IN (SELECT TOP 2 Id FROM dbo.' + QUOTENAME(@t) + N' ORDER BY NEWID());
            -- DELETE the newest generated row (keeps the seeded 10k baseline intact)
            DELETE FROM dbo.' + QUOTENAME(@t) + N' WHERE Id = (SELECT MAX(Id) FROM dbo.' + QUOTENAME(@t) + N' WHERE Id > 10000);';
        EXEC sys.sp_executesql @sql;

        SET @k += 1;
    END

    WAITFOR DELAY '00:00:01';
END
