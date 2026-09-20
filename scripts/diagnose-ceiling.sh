#!/usr/bin/env bash
# Name the thing that limits hold throughput, with evidence rather than with reasoning.
#
# The capacity sweep says the ceiling is between 200 and 300 requests a second: at 200 the p99 is
# 24 ms and nothing is shed, at 300 the p50 is exactly the 2 s pool timeout and a third of the load
# is refused. A 120-connection pool serving 16 ms requests should manage far more than that, so
# something is holding connections for much longer than a hold takes, or taking locks that make it
# take longer. This runs at the rate that breaks and samples what the replicas say about
# themselves while it does.
#
# What it can distinguish:
#   - connections active at the limit with few waiting  -> the pool is big enough, the work is slow
#   - connections active at the limit with many waiting -> the pool is the limit
#   - sweeper or relay durations rising with load       -> background work is eating the pool
#   - acquire time high, usage time low                 -> contention is in getting a connection
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}"
RATE="${WILLCALL_DIAGNOSE_RATE:-300}"
DURATION="${WILLCALL_DIAGNOSE_DURATION:-60s}"
DATE="$(date -u +%Y-%m-%d)"
OUT_DIR="load/results/${DATE}/ceiling-${RATE}-$(date -u +%H%M%S)"
mkdir -p "$OUT_DIR"

printf 'diagnosing the ceiling at %s requests/s for %s\n' "$RATE" "$DURATION"
printf 'results: %s\n\n' "$OUT_DIR"

sample() {
  ts="$(date -u +%s)"
  for port in 18081 18082 18083; do
    curl -fsS --max-time 2 "http://127.0.0.1:${port}/actuator/prometheus" 2>/dev/null \
      | awk -v ts="$ts" -v replica="app${port: -1}" '
        /^hikaricp_connections|^willcall_sweeper|^willcall_outbox|^willcall_hold|^jvm_threads_live/ && $0 !~ /^#/ {
          print ts, replica, $0
        }'
  done
}

: > "$OUT_DIR/metrics.txt"
( while :; do sample >> "$OUT_DIR/metrics.txt" 2>/dev/null; sleep 1; done ) &
SAMPLER_PID=$!
# shellcheck disable=SC2064
trap "kill $SAMPLER_PID 2>/dev/null || true" EXIT

# What PostgreSQL thinks it is doing, sampled alongside: an application that believes it is waiting
# on the database and a database that believes it is idle is a different problem from both of them
# being busy.
( while :; do
    printf '=== %s\n' "$(date -u +%s)"
    docker exec -i willcall-postgres-1 psql -U willcall -d willcall -At -F'|' -c "
      select coalesce(state, 'null'), coalesce(wait_event_type, 'none'), count(*)
      from pg_stat_activity where datname = 'willcall'
      group by 1, 2 order by 3 desc;" 2>/dev/null
    sleep 2
  done ) >> "$OUT_DIR/pg-activity.txt" 2>&1 &
PG_PID=$!
# shellcheck disable=SC2064
trap "kill $SAMPLER_PID $PG_PID 2>/dev/null || true" EXIT

WILLCALL_BASE_URL="$BASE_URL" SCENARIO_OUT="$OUT_DIR" \
  k6 run --quiet --no-thresholds \
    --summary-export "$OUT_DIR/summary.json" \
    --summary-trend-stats 'min,med,avg,p(90),p(95),p(99),max' \
    -e "BASE_URL=$BASE_URL" -e "SCENARIO_OUT=$OUT_DIR" \
    -e "HOLD_RATE=$RATE" -e "HOLD_DURATION=$DURATION" \
    load/scripts/holds.ts > "$OUT_DIR/k6.log" 2>&1 || true

kill "$SAMPLER_PID" "$PG_PID" 2>/dev/null || true
sleep 1

python3 - "$OUT_DIR" "$RATE" <<'DIAG'
import collections, json, os, re, statistics, sys

out_dir, rate = sys.argv[1], int(sys.argv[2])

# Gauges: the peak and the median while under load, per replica then summed.
gauges = collections.defaultdict(lambda: collections.defaultdict(list))
quantiles = collections.defaultdict(list)
counters = {}
for line in open(os.path.join(out_dir, 'metrics.txt'), errors='replace'):
    parts = line.split(' ', 2)
    if len(parts) < 3:
        continue
    ts, replica, metric = parts[0], parts[1], parts[2].strip()
    name, _, value = metric.rpartition(' ')
    try:
        value = float(value)
    except ValueError:
        continue
    bare = name.split('{', 1)[0]
    if 'quantile=' in name:
        # A Micrometer summary's quantile lines are not gauges to be summed across replicas; they
        # are pre-computed percentiles and the highest one anybody reported is what matters.
        q = re.search(r'quantile="([0-9.]+)"', name).group(1)
        quantiles[f'{bare}@p{q}'].append(value)
    elif bare.endswith('_total') or bare.endswith('_count') or bare.endswith('_sum'):
        counters.setdefault((replica, name), [value, value])[1] = value
    else:
        gauges[bare][int(ts)].append(value)

def summarise(bare):
    per_ts = {ts: sum(v) for ts, v in gauges.get(bare, {}).items()}
    if not per_ts:
        return None
    values = list(per_ts.values())
    return {'median': statistics.median(values), 'max': max(values)}

# Mean duration of a timed operation over the run: seconds_sum delta / seconds_count delta.
def counter_delta(prefix):
    return sum(last - first for (_, name), (first, last) in counters.items()
               if name.startswith(prefix))


def mean_seconds(prefix):
    total, count = 0.0, 0.0
    for (_, name), (first, last) in counters.items():
        if not name.startswith(prefix):
            continue
        if '_sum' in name.split('{', 1)[0]:
            total += last - first
        elif '_count' in name.split('{', 1)[0]:
            count += last - first
    return (total / count) if count else None

k6 = json.load(open(os.path.join(out_dir, 'summary.json')))['metrics']
hold = k6.get('willcall_hold_duration', {})
requests = k6.get('http_reqs', {}).get('count', 0)
granted = k6.get('willcall_holds_granted', {}).get('count', 0)
refused = k6.get('willcall_holds_refused', {}).get('count', 0)

# What PostgreSQL was doing, by state.
pg = collections.Counter()
for line in open(os.path.join(out_dir, 'pg-activity.txt'), errors='replace'):
    fields = line.strip().split('|')
    if len(fields) == 3 and fields[2].isdigit():
        pg[(fields[0], fields[1])] += int(fields[2])
samples = max(sum(1 for line in open(os.path.join(out_dir, 'pg-activity.txt')) if line.startswith('===')), 1)

report = {
    'offeredRatePerSecond': rate,
    'achievedRatePerSecond': k6.get('http_reqs', {}).get('rate'),
    'requests': requests,
    'granted': granted,
    'refused409': refused,
    'shedOrFailed': max(requests - granted - refused, 0),
    'holdP50Ms': hold.get('med'),
    'holdP99Ms': hold.get('p(99)'),
    'poolActive': summarise('hikaricp_connections_active'),
    'poolIdle': summarise('hikaricp_connections_idle'),
    'poolPending': summarise('hikaricp_connections_pending'),
    'poolMax': summarise('hikaricp_connections_max'),
    'liveThreads': summarise('jvm_threads_live_threads'),
    'meanAcquireSeconds': mean_seconds('hikaricp_connections_acquire_seconds'),
    'meanUsageSeconds': mean_seconds('hikaricp_connections_usage_seconds'),
    'meanSweeperSeconds': mean_seconds('willcall_sweeper_duration_seconds'),
    'sweeperP99Seconds': max(quantiles.get('willcall_sweeper_duration_seconds@p0.99', [0]) or [0]),
    # Every time the pool refused to hand out a connection within its 2 s timeout. This is the
    # count that turns "the pool is the bottleneck" from a reading of a graph into a number.
    'poolAcquisitionTimeouts': counter_delta('hikaricp_connections_timeout_total'),
    'outboxPublished': counter_delta('willcall_outbox_published_total'),
    'postgresActivityMeanBySt': {f'{s}/{w}': round(c / samples, 1) for (s, w), c in pg.most_common(8)},
}
json.dump(report, open(os.path.join(out_dir, 'ceiling.json'), 'w'), indent=2)

def ms(v):
    return f'{v * 1000:.1f} ms' if v is not None else '—'

print('')
print(f"offered / achieved   : {rate} / {report['achievedRatePerSecond']:.0f} requests per second")
print(f"granted / 409 / shed : {granted:,.0f} / {refused:,.0f} / {report['shedOrFailed']:,.0f}")
print(f"hold p50 / p99       : {hold.get('med', 0):.1f} / {hold.get('p(99)', 0):.1f} ms")
for label, key in (('pool active', 'poolActive'), ('pool idle', 'poolIdle'),
                   ('pool waiting', 'poolPending'), ('pool size', 'poolMax')):
    v = report[key]
    print(f"{label:21}: median {v['median']:.0f}, peak {v['max']:.0f}" if v else f'{label:21}: —')
print(f"mean acquire         : {ms(report['meanAcquireSeconds'])}")
print(f"mean connection held : {ms(report['meanUsageSeconds'])}")
print(f"mean sweeper pass    : {ms(report['meanSweeperSeconds'])}"
      f"  (p99 {ms(report['sweeperP99Seconds'])})")
print(f"pool acquire timeouts: {report['poolAcquisitionTimeouts']:,.0f}")
print(f"outbox published     : {report['outboxPublished']:,.0f}")
print(f"postgres backends    : {report['postgresActivityMeanBySt']}")
DIAG

{
  printf '# Run context: ceiling diagnosis at %s requests/s\n\n' "$RATE"
  printf '**local Docker Compose, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Collected (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Offered rate | %s requests/s |\n' "$RATE"
  printf '| Duration | %s |\n' "$DURATION"
  printf '| Application | 3 replicas, 2 vCPU / 2 GiB each, 40 pool connections each |\n'
  printf '| Load generator | k6 on the same host, competing for CPU |\n'
} > "$OUT_DIR/run-context.md"

printf '\nwritten to %s\n' "$OUT_DIR"
