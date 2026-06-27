#!/usr/bin/env bash
# Benchmark the async integrity validation job (#154) against a project (ideally one with the
# large PerfOrders fixture loaded). Logs in, starts a run, polls it to completion, and reports
# wall-clock total, per-poll progress, and the final pass/fail tally.
#
# Usage:
#   ./benchmark.sh <PROJECT_ID>
# Env overrides:
#   API_BASE   (default http://localhost:8090/api/v1)
#   PF_USER    (default admin)
#   PF_PASS    (default admin)
#   POLL_SECS  (default 2)
#
# Requires: curl, jq.
set -euo pipefail

PROJECT_ID="${1:?Usage: ./benchmark.sh <PROJECT_ID>}"
API_BASE="${API_BASE:-http://localhost:8090/api/v1}"
PF_USER="${PF_USER:-admin}"
PF_PASS="${PF_PASS:-admin}"
POLL_SECS="${POLL_SECS:-2}"

command -v jq  >/dev/null || { echo "jq is required"; exit 1; }
command -v curl >/dev/null || { echo "curl is required"; exit 1; }

echo "→ Logging in to $API_BASE as $PF_USER"
TOKEN=$(curl -fsS -X POST "$API_BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$PF_USER\",\"password\":\"$PF_PASS\"}" | jq -r '.token')
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || { echo "login failed"; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

echo "→ Starting validation run for project $PROJECT_ID"
START_EPOCH=$(date +%s)
RUN=$(curl -fsS -X POST "${AUTH[@]}" "$API_BASE/projects/$PROJECT_ID/validation")
RUN_ID=$(echo "$RUN" | jq -r '.id')
TOTAL=$(echo "$RUN" | jq -r '.totalTables')
echo "  run=$RUN_ID  tables=$TOTAL"

STATUS="PENDING"
while [ "$STATUS" = "PENDING" ] || [ "$STATUS" = "RUNNING" ]; do
  sleep "$POLL_SECS"
  RUN=$(curl -fsS "${AUTH[@]}" "$API_BASE/projects/$PROJECT_ID/validation/runs/$RUN_ID")
  STATUS=$(echo "$RUN" | jq -r '.status')
  DONE=$(echo "$RUN" | jq -r '.completedTables')
  PASS=$(echo "$RUN" | jq -r '.passed')
  FAIL=$(echo "$RUN" | jq -r '.failed')
  ELAPSED=$(( $(date +%s) - START_EPOCH ))
  printf '  [%4ds] %-9s %s/%s tables  pass=%s fail=%s\n' "$ELAPSED" "$STATUS" "$DONE" "$TOTAL" "$PASS" "$FAIL"
done

WALL=$(( $(date +%s) - START_EPOCH ))
echo
echo "=== Result ==="
echo "$RUN" | jq -r '"status      : \(.status)
tables      : \(.completedTables)/\(.totalTables)
passed      : \(.passed)
failed      : \(.failed)
startedAt   : \(.startedAt)
finishedAt  : \(.finishedAt)
error       : \(.error // "—")"'
echo "wall-clock  : ${WALL}s (client-observed, includes queue wait + polling granularity)"
echo
echo "Per-table rows:"
echo "$RUN" | jq -r '.results[] | "  \(.schema).\(.table)  src=\(.sourceRows) tgt=\(.targetRows) missing=\(.missingRows) extra=\(.extraRows) [\(.status)]"'
