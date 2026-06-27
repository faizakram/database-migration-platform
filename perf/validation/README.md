# Validation scale benchmark (#154)

A repeatable harness to prove the integrity validation job (epic #149) holds up on
multi-million-row tables, and to detect regressions.

It exercises the async job added in #150 (start → poll → stream), the set-based
missing-row check (#151), and the timeout / approximate-count options (#152).

## What's here

| File | Purpose |
|---|---|
| `seed-large-source.sql` | Seeds a large single-PK table (`dbo.PerfOrders`) into a dedicated SQL Server DB and enables CDC. |
| `inject-target-drift.sql` | Injects a *known* amount of missing/extra rows into the migrated target, to check the report's accuracy at scale. |
| `benchmark.sh` | Logs into the API, starts a validation run, polls to completion, prints timing + tally. |

## Prerequisites

- The full stack running (`deploy/up.sh`) — backend reachable at `http://localhost:8090`.
- A SQL Server source reachable for seeding (see `debezium-setup/test-data/`).
- `curl` and `jq` on the PATH.

## 1. Seed the large source fixture

```bash
SA='Str0ngP@ssw0rd!'
sqlcmd -S localhost,1433 -U sa -P "$SA" \
  -v Db="PerfBench" Rows="5000000" \
  -i perf/validation/seed-large-source.sql
```

`Rows` is parameterized — start at 1–5M, scale up as needed. Seeding is batched at 1M rows.

## 2. Create + run a migration project

Through the UI (or API): add the SQL Server `PerfBench` source and your PostgreSQL target,
select `dbo.PerfOrders`, and run the migration to completion (snapshot + CDC caught up).

## 3. (Optional) Inject known target drift

After the target is loaded, introduce a known number of missing/extra rows so you can confirm
the report counts them correctly under load. Match `:tbl` to your naming strategy
(`perf_orders` for snake_case):

```bash
psql "$TARGET_URL" -v schema=public -v tbl=perf_orders -f perf/validation/inject-target-drift.sql
```

Expected on the next run: `missing_rows ≈ 50`, `extra_rows ≈ 25`.

## 4. Benchmark the validation run

```bash
perf/validation/benchmark.sh <PROJECT_ID>
# overrides: API_BASE, PF_USER, PF_PASS, POLL_SECS
```

It prints live progress, then a summary with the server-side `startedAt`/`finishedAt`
(authoritative job duration) and a client-observed wall-clock.

## Tuning knobs under test (`platform.validation.*`)

| key | env | default |
|---|---|---|
| `query-timeout-seconds` | `VALIDATION_QUERY_TIMEOUT_SECONDS` | 300 |
| `missing-sample-size` | `VALIDATION_MISSING_SAMPLE_SIZE` | 1000 |
| `approximate-counts` | `VALIDATION_APPROXIMATE_COUNTS` | false |

Re-run the benchmark with `VALIDATION_APPROXIMATE_COUNTS=true` to compare exact vs. approximate
counting cost on large tables.

## Baseline & SLA

Record results here so regressions are visible. (Fill in after the first real run on
representative hardware — left blank intentionally rather than guessed.)

| Date | Rows | Tables | approx-counts | Job duration | Notes / hardware |
|---|---|---|---|---|---|
| _TBD_ | 5,000,000 | 1 | false | _TBD_ | initial baseline |
| _TBD_ | 5,000,000 | 1 | true  | _TBD_ | approximate counts |

**Proposed SLA (ratify after baseline):** a single 5M-row table validates within the default
300s query timeout with exact counts; approximate-count mode completes in a small fraction of that.
