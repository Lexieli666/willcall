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
  # -i matters: without it docker does not attach stdin, psql reads EOF, the query never runs, the
  # report comes back empty and every check "passes". That is the second time this script has
  # silently reported success while checking nothing, which is why the query below now carries a
  # sentinel row that must come back.
  PSQL=(docker run --rm -i --network host -e "PGPASSWORD=$PGPASSWORD" postgres:16-alpine
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

# All seven checks in one session.
#
# They used to be seven separate invocations, which on a host without a local psql meant seven
# container starts — roughly ten seconds of process creation per invocation, paid fifty times over
# in the flash-sale suite and on every CI job. One session runs them as one query and prints a row
# per violation.
#
# The SQL below is the authority. InvariantController carries the same checks so the deployed
# service can verify itself when the database is in a private subnet, and
# ChaosIntegrationTest asserts the two lists have not drifted apart.
REPORT="$(
  "${PSQL[@]}" <<'SQL'
\set ON_ERROR_STOP on

with violations as (

  -- A sentinel that is always present. If the report comes back without it, the query did not
  -- run - a closed stdin, a connection dropped mid-statement, a typo in the invocation - and the
  -- script fails loudly instead of reporting seven passes over nothing. This exists because the
  -- failure mode it guards against has happened twice.
  select '__checks_ran__' as check_name, 'sentinel' as detail

  union all

  select 'confirmed + unexpired holds <= capacity' as check_name,
         e.id || ' ' || e.name || ' capacity=' || e.capacity
           || ' confirmed=' || counts.confirmed || ' held=' || counts.held as detail
  from events e
  join lateral (
    select count(*) filter (where s.status = 'SOLD') as confirmed,
           count(*) filter (where s.status = 'HELD') as held
    from seats s where s.event_id = e.id
  ) counts on true
  where counts.confirmed + counts.held > e.capacity

  union all
  select 'no seat has more than one active allocation',
         seat_id || ' has ' || count(*) || ' active holds'
  from holds where status = 'ACTIVE'
  group by seat_id having count(*) > 1

  union all
  select 'no seat is both sold and actively held', s.id::text
  from seats s
  join holds h on h.seat_id = s.id and h.status = 'ACTIVE'
  where s.status = 'SOLD'

  union all
  select 'every sold seat has exactly one confirmed order line',
         s.id || ' has ' || count(o.id) || ' confirmed order lines'
  from seats s
  left join order_lines ol on ol.seat_id = s.id
  left join orders o on o.id = ol.order_id and o.status = 'CONFIRMED'
  where s.status = 'SOLD'
  group by s.id having count(o.id) <> 1

  union all
  select 'no held seat lacks a matching active hold row', s.id::text
  from seats s
  where s.status = 'HELD'
    and not exists (select 1 from holds h where h.seat_id = s.id and h.status = 'ACTIVE')

  union all
  select 'no active hold points at a seat that is not held',
         h.id || ' -> seat ' || s.id || ' is ' || s.status
  from holds h join seats s on s.id = h.seat_id
  where h.status = 'ACTIVE' and s.status <> 'HELD'

  union all
  select 'capacity matches the number of sellable seats',
         e.id || ' capacity=' || e.capacity || ' sellable='
           || (select count(*) from seats s where s.event_id = e.id and s.status <> 'BLOCKED')
  from events e
  where e.capacity <> (select count(*) from seats s where s.event_id = e.id and s.status <> 'BLOCKED')

)
select check_name || ' | ' || detail from violations limit 200;
SQL
)" || {
  printf 'FAILED: the invariant query could not run\n%s\n' "$REPORT" >&2
  exit 2
}

if ! printf '%s\n' "$REPORT" | grep -qF '__checks_ran__ | sentinel'; then
  printf 'FAILED: the invariant query returned no sentinel row, so it did not run.\n' >&2
  printf 'Output was:\n%s\n' "$REPORT" >&2
  exit 2
fi

CHECKS=(
  'confirmed + unexpired holds <= capacity'
  'no seat has more than one active allocation'
  'no seat is both sold and actively held'
  'every sold seat has exactly one confirmed order line'
  'no held seat lacks a matching active hold row'
  'no active hold points at a seat that is not held'
  'capacity matches the number of sellable seats'
)

failures=0
for check in "${CHECKS[@]}"; do
  matches="$(printf '%s\n' "$REPORT" | grep -F "$check | " || true)"
  if [ -n "$matches" ]; then
    printf 'INVARIANT VIOLATED: %s\n%s\n' "$check" "$matches" >&2
    failures=$((failures + 1))
  else
    printf 'ok: %s\n' "$check"
  fi
done

printf '\n%s checks run\n' "${#CHECKS[@]}"

if [ "$failures" -gt 0 ]; then
  printf '%s invariant check(s) failed\n' "$failures" >&2
  exit 1
fi

printf 'all invariant checks passed\n'
