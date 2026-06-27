-- Inject a KNOWN amount of drift into the migrated target table, so the integrity report's
-- accuracy can be checked at scale (#154). Run AFTER the migration has loaded dbo.PerfOrders
-- into the target (PostgreSQL), e.g.:
--   psql "$TARGET_URL" -v schema=public -v tbl=perf_orders -f inject-target-drift.sql
--
-- Adjust :schema / :tbl to match your project's target schema + naming strategy
-- (snake_case → perf_orders, PRESERVE → "PerfOrders", etc.).
--
-- Expected effect on the next integrity run for this table:
--   missing_rows ≈ 50   (rows deleted from target but present in source)
--   extra_rows   ≈ 25   (rows present in target but not in source)
--   duplicate_keys: 0   (PK prevents dupes here; documented for completeness)

\set tgt :schema '.' :tbl

-- 50 deletions → these source keys become "missing" on the target.
DELETE FROM :tgt
WHERE id IN (SELECT id FROM :tgt ORDER BY id LIMIT 50);

-- 25 extras → ids far beyond the source range, so they have no source match.
INSERT INTO :tgt (id, customer_id, amount, status, created_at, note)
SELECT g, 0, 0.00, 'EXTRA', now(), concat('injected-extra-', g)
FROM generate_series(900000001, 900000025) AS g;

SELECT count(*) AS target_rows_after_drift FROM :tgt;
