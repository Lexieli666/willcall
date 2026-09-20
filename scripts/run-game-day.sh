#!/usr/bin/env bash
# Run one game-day scenario against the live stack while traffic is flowing, capturing what the
# dashboard would have shown.
#
#   ./scripts/run-game-day.sh kill-replica
#   ./scripts/run-game-day.sh exhaust-pool
#   ./scripts/run-game-day.sh restart-redis
#   ./scripts/run-game-day.sh inject-latency
#
# Traffic is generated for the duration, because a failure with no load is a different failure.
# Metrics are sampled every two seconds throughout, so the postmortem can be written from what
# happened rather than from what was remembered.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SCENARIO="${1:?usage: run-game-day.sh <kill-replica|exhaust-pool|restart-redis|inject-latency>}"
BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}"
DATE="$(date -u +%Y-%m-%d)"
OUT_DIR="load/results/${DATE}/gameday-${SCENARIO}-$(date -u +%H%M%S)"
mkdir -p "$OUT_DIR"

LOAD_SECONDS="${WILLCALL_GAMEDAY_LOAD_SECONDS:-120}"
FAULT_AT="${WILLCALL_GAMEDAY_FAULT_AT:-40}"
FAULT_FOR="${WILLCALL_GAMEDAY_FAULT_FOR:-40}"

printf 'scenario   : %s\n' "$SCENARIO"
printf 'results    : %s\n' "$OUT_DIR"
printf 'load       : %ss, fault injected at %ss for %ss\n\n' "$LOAD_SECONDS" "$FAULT_AT" "$FAULT_FOR"

# ------------------------------------------------------------------ sampling

sample_metrics() {
  local ts
  ts="$(date -u +%s)"
  for port in 18081 18082 18083; do
    curl -fsS --max-time 2 "http://127.0.0.1:${port}/actuator/prometheus" 2>/dev/null \
      | awk -v ts="$ts" -v replica="app${port: -1}" '
        /^willcall_|^hikaricp_connections|^http_server_requests_seconds_count/ && $0 !~ /^#/ {
          print ts, replica, $0
        }'
  done
}

: > "$OUT_DIR/metrics.txt"
(
  while :; do
    sample_metrics >> "$OUT_DIR/metrics.txt" 2>/dev/null
    sleep 2
  done
) &
SAMPLER_PID=$!

# ------------------------------------------------------------------ traffic

EVENT_ID="$(curl -fsS -X POST -H 'Content-Type: application/json' -d '{
  "venueName": "Game Day Arena",
  "eventName": "Game day",
  "holdTtlSeconds": 30,
  "maxSeatsPerOrder": 4,
  "status": "ON_SALE",
  "priceTiers": [{"name": "Standard", "amountCents": 3000, "currency": "USD"}],
  "sections": [{"name": "Floor", "rowCount": 200, "seatsPerRow": 50, "priceTierName": "Standard"}]
}' "$BASE_URL/api/events" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
printf 'event: %s\n\n' "$EVENT_ID"

WILLCALL_BASE_URL="$BASE_URL" \
SCENARIO_OUT="$OUT_DIR" \
  k6 run --quiet \
    --summary-export "$OUT_DIR/summary.json" \
    --summary-trend-stats 'min,med,avg,p(90),p(95),p(99),max' \
    -e "BASE_URL=$BASE_URL" \
    -e "SCENARIO_OUT=$OUT_DIR" \
    -e "HOLD_EVENT_ID=$EVENT_ID" \
    -e "HOLD_RATE=${WILLCALL_GAMEDAY_RATE:-150}" \
    -e "HOLD_DURATION=${LOAD_SECONDS}s" \
    load/scripts/holds.ts > "$OUT_DIR/k6.log" 2>&1 &
K6_PID=$!

# ------------------------------------------------------------------ the fault

cleanup() {
  kill "$SAMPLER_PID" 2>/dev/null || true
}
trap cleanup EXIT

printf 'waiting %ss before injecting the fault\n' "$FAULT_AT"
sleep "$FAULT_AT"

FAULT_START="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
printf '%s  INJECTING: %s\n' "$FAULT_START" "$SCENARIO" | tee -a "$OUT_DIR/timeline.txt"

case "$SCENARIO" in
  kill-replica)
    docker compose kill app2 >/dev/null 2>&1
    printf '%s  app2 killed\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"
    sleep "$FAULT_FOR"
    docker compose up -d app2 >/dev/null 2>&1
    printf '%s  app2 restarted\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"
    ;;

  exhaust-pool)
    # One external session takes ACCESS EXCLUSIVE on seats and holds it. Every reservation
    # transaction then blocks inside the database while still holding its pool connection, so all
    # forty fill up on each replica and the next request waits out the 2 s connection timeout.
    #
    # The first version of this scenario opened 130 idle `pg_sleep` sessions instead, on the
    # reasoning that they would starve the pool. They do not: HikariCP's pool is client side, and
    # 130 server sessions against a 300-connection server leave the application's own 120
    # untouched. It was a fault that looked like the right one and injected nothing. The real
    # analogue of this outage is a long-running exclusive operation - a migration, a VACUUM FULL -
    # which is what this now does.
    docker exec -d willcall-postgres-1 psql -U willcall -d willcall -c \
      "begin; lock table seats in access exclusive mode; select pg_sleep($FAULT_FOR); commit;" \
      >/dev/null 2>&1
    printf '%s  ACCESS EXCLUSIVE on seats held for %ss\n' \
      "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$FAULT_FOR" | tee -a "$OUT_DIR/timeline.txt"
    sleep "$((FAULT_FOR + 5))"
    # Anything still holding the lock is cleared, so a slow exit cannot bleed into the recovery
    # window and be read as a failure to recover.
    docker exec -i willcall-postgres-1 psql -U willcall -d willcall -c \
      "select pg_terminate_backend(pid) from pg_stat_activity
       where query like '%access exclusive%' and pid <> pg_backend_pid();" >/dev/null 2>&1 || true
    printf '%s  lock released\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"
    ;;

  restart-redis)
    docker compose restart redis >/dev/null 2>&1
    printf '%s  redis restarted\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"
    sleep "$FAULT_FOR"
    ;;

  inject-latency)
    # tc needs NET_ADMIN, which the container does not have by default. Falling back to pausing
    # the database is a different fault with a similar shape, and saying so is better than
    # pretending the intended one ran.
    if docker exec willcall-app1-1 tc qdisc add dev eth0 root netem delay 200ms >/dev/null 2>&1; then
      printf '%s  200ms added to app1 egress\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"
      sleep "$FAULT_FOR"
      docker exec willcall-app1-1 tc qdisc del dev eth0 root >/dev/null 2>&1
      printf '%s  latency removed\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"
    else
      printf '%s  tc unavailable (needs NET_ADMIN); pausing PostgreSQL instead, which is a\n' \
        "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"
      printf '        different fault with a similar shape and is recorded as such\n' | tee -a "$OUT_DIR/timeline.txt"
      docker pause willcall-postgres-1 >/dev/null 2>&1
      sleep "$((FAULT_FOR / 4))"
      docker unpause willcall-postgres-1 >/dev/null 2>&1
      printf '%s  PostgreSQL unpaused\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"
      sleep "$FAULT_FOR"
    fi
    ;;

  *)
    printf 'unknown scenario: %s\n' "$SCENARIO" >&2
    kill "$K6_PID" 2>/dev/null || true
    exit 1
    ;;
esac

printf '%s  fault window closed; waiting for the load to finish\n' \
  "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" | tee -a "$OUT_DIR/timeline.txt"

wait "$K6_PID"
K6_EXIT=$?
kill "$SAMPLER_PID" 2>/dev/null || true
trap - EXIT

# ------------------------------------------------------------------ afterwards

printf '\n--- invariants after the scenario ---\n'
./scripts/verify-invariants.sh 2>&1 | tee "$OUT_DIR/verify-invariants.log" | tail -3
INVARIANT_EXIT="${PIPESTATUS[0]}"

python3 - "$OUT_DIR" "$SCENARIO" "$K6_EXIT" "$INVARIANT_EXIT" "$FAULT_START" <<'PY'
import collections, json, os, re, sys

out_dir, scenario, k6_exit, invariant_exit, fault_start = sys.argv[1:6]

# Status-code counts over the run, summed from the sampled counters.
#
# Last-minus-first is wrong the moment a replica restarts: its counters go back to zero, and the
# kill-replica scenario reported -93,580 responses with status 201 - a number that is not merely
# inaccurate but impossible, and that no amount of reading the chart would have explained. This
# walks the samples in order and adds each positive step, treating a step backwards as a restart
# and counting the new value from zero, which is what Prometheus' own increase() does. The resets
# are counted too, because "the replica restarted twice" is itself a finding.
previous = {}
totals = collections.Counter()
resets = collections.Counter()
for line in open(os.path.join(out_dir, 'metrics.txt'), errors='replace'):
    parts = line.split(' ', 2)
    if len(parts) < 3:
        continue
    _, replica, metric = parts
    metric = metric.strip()
    if not metric or metric.startswith('#'):
        continue
    name, _, value = metric.rpartition(' ')
    try:
        value = float(value)
    except ValueError:
        continue
    key = (replica, name)
    if key not in previous:
        # The first sample is the baseline: work done before the run started is not this run's.
        previous[key] = value
        continue
    if value >= previous[key]:
        totals[key] += value - previous[key]
    else:
        totals[key] += value
        resets[replica] += 1
    previous[key] = value

by_status = collections.Counter()
for (replica, name), value in totals.items():
    if not name.startswith('http_server_requests_seconds_count'):
        continue
    status = re.search(r'status="(\d+)"', name)
    if not status:
        continue
    by_status[status.group(1)] += value

summary = {}
summary_path = os.path.join(out_dir, 'summary.json')
if os.path.exists(summary_path):
    metrics = json.load(open(summary_path)).get('metrics', {})
    summary = {
        'holdP99Ms': metrics.get('willcall_hold_duration', {}).get('p(99)'),
        'holdMaxMs': metrics.get('willcall_hold_duration', {}).get('max'),
        'holdsGranted': metrics.get('willcall_holds_granted', {}).get('count'),
        'holdsRefused': metrics.get('willcall_holds_refused', {}).get('count'),
        'errorRate': metrics.get('willcall_hold_errors', {}).get('rate'),
    }

report = {
    'scenario': scenario,
    'faultInjectedAt': fault_start,
    'k6ExitCode': int(k6_exit),
    'invariantsHeld': int(invariant_exit) == 0,
    'responsesByStatus': {k: round(v) for k, v in sorted(by_status.items())},
    # A counter that went backwards means that replica restarted mid-run. Zero here during
    # kill-replica would mean the fault did not land.
    'counterResetsByReplica': dict(sorted(resets.items())),
    'load': summary,
}
json.dump(report, open(os.path.join(out_dir, 'gameday.json'), 'w'), indent=2)

print('')
print(f"responses by status : {report['responsesByStatus']}")
print(f"counter resets      : {report['counterResetsByReplica'] or 'none'}")
print(f"invariants held     : {report['invariantsHeld']}")
if summary.get('holdP99Ms'):
    print(f"hold p99 / max (ms) : {summary['holdP99Ms']:.0f} / {summary['holdMaxMs']:.0f}")
PY

{
  printf '# Run context: game day, %s\n\n' "$SCENARIO"
  printf '**local Docker Compose, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Scenario | `%s` |\n' "$SCENARIO"
  printf '| Started (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Fault injected at | %s |\n' "$FAULT_START"
  printf '| Load | holds scenario at %s/s for %ss |\n' "${WILLCALL_GAMEDAY_RATE:-150}" "$LOAD_SECONDS"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Host logical cores | %s |\n' "$(nproc)"
  printf '\n## Caveats\n\n'
  printf -- '- Local Compose cannot exercise Application Load Balancer behaviour, cross-availability-zone failure, or RDS failover. Those remain outstanding and are listed in PROGRESS.md.\n'
  printf -- '- Metrics are sampled every two seconds from each replica directly. A counter that resets because a replica restarted shows as a negative delta; the kill-replica scenario is read with that in mind.\n'
} > "$OUT_DIR/run-context.md"

printf '\nwritten to %s\n' "$OUT_DIR"
