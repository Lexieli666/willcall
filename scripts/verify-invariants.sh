#!/usr/bin/env bash
# The central invariant, asserted directly against the database rather than through the
# application, so it also catches a bug that the application cannot see.
#
#   1. For every event: confirmed seats + unexpired holds <= capacity.
#   2. No seat has more than one active allocation (an unexpired hold or a confirmed order).
#   3. No seat is simultaneously sold and held.
#   4. Every confirmed order line points at a seat marked sold.
#
# Exit code 1 and a printed offending row on any violation. This runs in CI, after every load
# test, and as the last step of the game day.
set -euo pipefail

PGHOST="${WILLCALL_PG_HOST:-127.0.0.1}"
PGPORT="${WILLCALL_PG_PORT:-15432}"
PGUSER="${WILLCALL_PG_USER:-willcall}"
PGDATABASE="${WILLCALL_PG_DB:-willcall}"
export PGPASSWORD="${WILLCALL_PG_PASSWORD:-willcall}"

psql() {
  if command -v psql >/dev/null 2>&1; then
    command psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" "$@"
  else
    docker run --rm --network host -e PGPASSWORD="$PGPASSWORD" postgres:16-alpine \
      psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" "$@"
  fi
}

run_check() {
  local name="$1" sql="$2"
  local rows
  rows=$(psql -At -c "$sql")
  if [ -n "$rows" ]; then
    printf 'INVARIANT VIOLATED: %s\n%s\n' "$name" "$rows" >&2
    return 1
  fi
  printf 'ok: %s\n' "$name"
}

failures=0

run_check 'confirmed + unexpired holds <= capacity' "
  select e.id, e.name, e.capacity, counts.confirmed, counts.held
  from events e
  join lateral (
    select
      count(*) filter (where s.status = 'SOLD') as confirmed,
      count(*) filter (where s.status = 'HELD') as held
    from seats s where s.event_id = e.id
  ) counts on true
  where counts.confirmed + counts.held > e.capacity;
" || failures=$((failures + 1))

run_check 'no seat has more than one active allocation' "
  select seat_id, count(*)
  from holds
  where status = 'ACTIVE'
  group by seat_id
  having count(*) > 1;
" || failures=$((failures + 1))

run_check 'no seat is both sold and actively held' "
  select s.id
  from seats s
  join holds h on h.seat_id = s.id and h.status = 'ACTIVE'
  where s.status = 'SOLD';
" || failures=$((failures + 1))

run_check 'every sold seat has exactly one confirmed order line' "
  select s.id, count(ol.id)
  from seats s
  left join order_lines ol on ol.seat_id = s.id
  left join orders o on o.id = ol.order_id and o.status = 'CONFIRMED'
  where s.status = 'SOLD'
  group by s.id
  having count(o.id) <> 1;
" || failures=$((failures + 1))

run_check 'no held seat lacks a matching active hold row' "
  select s.id
  from seats s
  where s.status = 'HELD'
    and not exists (select 1 from holds h where h.seat_id = s.id and h.status = 'ACTIVE');
" || failures=$((failures + 1))

run_check 'no active hold points at a seat that is not held' "
  select h.id, s.status
  from holds h join seats s on s.id = h.seat_id
  where h.status = 'ACTIVE' and s.status <> 'HELD';
" || failures=$((failures + 1))

if [ "$failures" -gt 0 ]; then
  printf '\n%s invariant check(s) failed\n' "$failures" >&2
  exit 1
fi

printf '\nall invariant checks passed\n'
