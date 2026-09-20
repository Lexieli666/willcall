#!/usr/bin/env bash
# The central invariant, asserted directly against the database rather than through the
# application, so it also catches a bug that the application cannot see.
#
#   1. For every event: confirmed seats + unexpired holds <= capacity.
#   2. No seat has more than one active allocation.
#   3. No seat is simultaneously sold and held.
#   4. Every sold seat sits on exactly one confirmed order line.
#   5. Every HELD seat has an active hold, and every active hold points at a HELD seat.
#   6. An event's capacity equals its number of sellable seats.
#
# Exit code 1 and the offending rows printed on any violation. Runs in CI, after every load test,
# and as the last step of the game day.
#
# The same SQL exists in InvariantController so the deployed service can check itself when the
# database sits in a private subnet. This file is the authority: it needs no application, so it
# catches a violation an application bug would hide. ChaosIntegrationTest asserts the two lists
# have not drifted apart.
#
# A note on how this script used to lie. It defined a shell function named `psql` and then asked
# `command -v psql` whether a client was installed. `command -v` finds functions, so the answer
# was always yes, the real binary was missing, every query failed, every check saw empty output
# and reported "ok". A checker that passes when it cannot reach the database is worse than no
# checker. The client is now resolved once, with `type -P`, and a connectivity probe runs before
# any check.
set -euo pipefail

PGHOST="${WILLCALL_PG_HOST:-127.0.0.1}"
PGPORT="${WILLCALL_PG_PORT:-15432}"
PGUSER="${WILLCALL_PG_USER:-willcall}"
PGDATABASE="${WILLCALL_PG_DB:-willcall}"
export PGPASSWORD="${WILLCALL_PG_PASSWORD:-willcall}"

PSQL_BIN="$(type -P psql || true)"

if [ -n "$PSQL_BIN" ]; then
  PSQL=("$PSQL_BIN" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -At)
  printf 'using local psql: %s\n' "$PSQL_BIN"
elif command -v docker >/dev/null 2>&1; then
  PSQL=(docker run --rm --network host -e "PGPASSWORD=$PGPASSWORD" postgres:16-alpine
        psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -At)
  printf 'using psql from the postgres:16-alpine image\n'
else
  printf 'FAILED: no psql binary and no docker to borrow one from\n' >&2
  exit 2
fi

# Connectivity probe. Without this, an unreachable database produces empty result sets and every
# check below reports success.
if ! probe="$("${PSQL[@]}" -c 'select 1' 2>&1)" || [ "$probe" != "1" ]; then
  printf 'FAILED: cannot query %s:%s/%s as %s\n%s\n' "$PGHOST" "$PGPORT" "$PGDATABASE" "$PGUSER" "$probe" >&2
  exit 2
fi

# The tables must exist, or "no rows" again means "nothing was checked".
missing="$("${PSQL[@]}" -c "
  select string_agg(t, ', ')
  from unnest(array['events','seats','holds','hold_groups','orders','order_lines']) as t
  where to_regclass('public.' || t) is null;
")"
if [ -n "$missing" ]; then
  printf 'FAILED: these tables do not exist, so nothing can be checked: %s\n' "$missing" >&2
  exit 2
fi

failures=0
checks_run=0

run_check() {
  local name="$1" sql="$2"
  local rows status
  set +e
  rows="$("${PSQL[@]}" -c "$sql" 2>&1)"
  status=$?
  set -e

  if [ "$status" -ne 0 ]; then
    printf 'ERROR running check "%s":\n%s\n' "$name" "$rows" >&2
    failures=$((failures + 1))
    return
  fi

  checks_run=$((checks_run + 1))
  if [ -n "$rows" ]; then
    printf 'INVARIANT VIOLATED: %s\n%s\n' "$name" "$rows" >&2
    failures=$((failures + 1))
    return
  fi
  printf 'ok: %s\n' "$name"
}

run_check 'confirmed + unexpired holds <= capacity' "
  select e.id || ' ' || e.name || ' capacity=' || e.capacity
         || ' confirmed=' || counts.confirmed || ' held=' || counts.held
  from events e
  join lateral (
    select
      count(*) filter (where s.status = 'SOLD') as confirmed,
      count(*) filter (where s.status = 'HELD') as held
    from seats s where s.event_id = e.id
  ) counts on true
  where counts.confirmed + counts.held > e.capacity;
"

run_check 'no seat has more than one active allocation' "
  select seat_id || ' has ' || count(*) || ' active holds'
  from holds
  where status = 'ACTIVE'
  group by seat_id
  having count(*) > 1;
"

run_check 'no seat is both sold and actively held' "
  select s.id::text
  from seats s
  join holds h on h.seat_id = s.id and h.status = 'ACTIVE'
  where s.status = 'SOLD';
"

run_check 'every sold seat has exactly one confirmed order line' "
  select s.id || ' has ' || count(o.id) || ' confirmed order lines'
  from seats s
  left join order_lines ol on ol.seat_id = s.id
  left join orders o on o.id = ol.order_id and o.status = 'CONFIRMED'
  where s.status = 'SOLD'
  group by s.id
  having count(o.id) <> 1;
"

run_check 'no held seat lacks a matching active hold row' "
  select s.id::text
  from seats s
  where s.status = 'HELD'
    and not exists (select 1 from holds h where h.seat_id = s.id and h.status = 'ACTIVE');
"

run_check 'no active hold points at a seat that is not held' "
  select h.id || ' -> seat ' || s.id || ' is ' || s.status
  from holds h join seats s on s.id = h.seat_id
  where h.status = 'ACTIVE' and s.status <> 'HELD';
"

run_check 'capacity matches the number of sellable seats' "
  select e.id || ' capacity=' || e.capacity || ' sellable='
         || (select count(*) from seats s where s.event_id = e.id and s.status <> 'BLOCKED')
  from events e
  where e.capacity <> (select count(*) from seats s where s.event_id = e.id and s.status <> 'BLOCKED');
"

printf '\n%s checks run\n' "$checks_run"

if [ "$failures" -gt 0 ]; then
  printf '%s invariant check(s) failed\n' "$failures" >&2
  exit 1
fi

if [ "$checks_run" -lt 7 ]; then
  printf 'FAILED: only %s of 7 checks actually ran\n' "$checks_run" >&2
  exit 1
fi

printf 'all invariant checks passed\n'
