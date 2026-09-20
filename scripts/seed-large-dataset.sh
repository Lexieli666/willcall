#!/usr/bin/env bash
# Seed a million users, fifty thousand events and a million historical orders, then save the query
# plans for the hot statements.
#
# The point is not the row count. It is that a query which is fast on a five-thousand-row table can
# be catastrophic on a million-row one, and the only way to find out which is to look at the plan
# at that size. Every plan this produces is committed, so a later change that turns an index scan
# into a sequential scan is visible in a diff.
#
# Generated entirely inside PostgreSQL with generate_series: shipping a million rows over the wire
# from a script would take longer than the measurement it enables.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

USERS="${WILLCALL_SEED_USERS:-1000000}"
EVENTS="${WILLCALL_SEED_EVENTS:-50000}"
ORDERS="${WILLCALL_SEED_ORDERS:-1000000}"

PGHOST="${WILLCALL_PG_HOST:-127.0.0.1}"
PGPORT="${WILLCALL_PG_PORT:-15432}"
PGUSER="${WILLCALL_PG_USER:-willcall}"
PGDATABASE="${WILLCALL_PG_DB:-willcall}"
export PGPASSWORD="${WILLCALL_PG_PASSWORD:-willcall}"

PSQL_BIN="$(type -P psql || true)"
if [ -n "$PSQL_BIN" ]; then
  PSQL=("$PSQL_BIN" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1)
elif command -v docker >/dev/null 2>&1; then
  PSQL=(docker run --rm -i --network host -e "PGPASSWORD=$PGPASSWORD" postgres:16-alpine
        psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1)
else
  printf 'FAILED: no psql and no docker\n' >&2
  exit 2
fi

DATE="$(date -u +%Y-%m-%d)"
OUT_DIR="load/results/${DATE}/query-plans"
mkdir -p "$OUT_DIR"

printf 'seeding %s users, %s events, %s orders\n' "$USERS" "$EVENTS" "$ORDERS"
printf 'this takes several minutes; it is doing the work inside PostgreSQL rather than over the wire\n\n'

START=$(date +%s)

"${PSQL[@]}" -v users="$USERS" -v events="$EVENTS" -v orders="$ORDERS" < scripts/sql/seed-large-dataset.sql

printf '\nseeded in %s seconds\n\n' "$(( $(date +%s) - START ))"

printf 'collecting query plans\n'
"${PSQL[@]}" < scripts/sql/hot-query-plans.sql > "$OUT_DIR/plans.txt" 2>&1

# The table sizes the plans were taken against: a plan without them says nothing.
"${PSQL[@]}" -c "
  select relname as table,
         to_char(n_live_tup, 'FM999,999,999') as live_rows,
         pg_size_pretty(pg_total_relation_size(relid)) as total_size
  from pg_stat_user_tables
  order by n_live_tup desc;
" > "$OUT_DIR/table-sizes.txt" 2>&1

{
  printf '# Run context: query plans at scale\n\n'
  printf '**local Docker Compose, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Collected (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Users seeded | %s |\n' "$USERS"
  printf '| Events seeded | %s |\n' "$EVENTS"
  printf '| Historical orders seeded | %s |\n' "$ORDERS"
  printf '| PostgreSQL | postgres:16-alpine, 4 vCPU, 8 GiB, `shared_buffers=1GB` |\n'
  printf '\n## Caveats\n\n'
  printf -- '- `EXPLAIN ANALYZE` executes the query, so the timings include the run. They are indicative rather than a benchmark; the plan shape is the thing to read.\n'
  printf -- '- The database was `ANALYZE`d immediately before collection, so the planner had current statistics. A plan taken with stale statistics is a plan for a database that does not exist.\n'
  printf -- '- Buffer counts are included (`EXPLAIN (ANALYZE, BUFFERS)`) because "fast" on a warm cache and "fast" on a cold one are different claims.\n'
} > "$OUT_DIR/run-context.md"

printf '\nplans written to %s\n' "$OUT_DIR"
grep -cE 'Seq Scan' "$OUT_DIR/plans.txt" | xargs -I{} printf 'sequential scans in the collected plans: {}\n'
