#!/usr/bin/env bash
# Find the sustainable hold rate by measuring several of them, instead of asserting one.
#
# A single run at 1,000 requests per second answers "did it hold up at 1,000", which on this
# hardware is "no": the first attempt shed two thirds of the offered load as 503. That is a useful
# fact and a useless measurement, because it says nothing about where the ceiling actually is. The
# sweep walks the rate up and publishes the whole curve, so the capacity model can name a measured
# holds/s per replica rather than a number that happened to be in the plan.
#
# Each step runs against a fresh event so that a step is not measuring the seat scarcity left by
# the one before it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}"
RATES="${WILLCALL_SWEEP_RATES:-100 200 300 400 600 800 1000}"
DURATION="${WILLCALL_SWEEP_DURATION:-45s}"
DATE="$(date -u +%Y-%m-%d)"
OUT_DIR="load/results/${DATE}/capacity-sweep-$(date -u +%H%M%S)"
mkdir -p "$OUT_DIR"

case "$BASE_URL" in
  *127.0.0.1*|*localhost*) LABEL='local Docker Compose, not AWS' ;;
  *) LABEL="$BASE_URL" ;;
esac

printf 'capacity sweep\n'
printf 'rates   : %s requests/s, %s each\n' "$RATES" "$DURATION"
printf 'results : %s\n\n' "$OUT_DIR"

for rate in $RATES; do
  step="$OUT_DIR/rate-$(printf '%04d' "$rate")"
  mkdir -p "$step"
  printf '\n--- %s requests/s ---\n' "$rate"
  set +e
  WILLCALL_BASE_URL="$BASE_URL" SCENARIO_OUT="$step" \
    k6 run --quiet \
      --summary-export "$step/summary.json" \
      --summary-trend-stats 'min,med,avg,p(90),p(95),p(99),max' \
      --no-thresholds \
      -e "BASE_URL=$BASE_URL" \
      -e "SCENARIO_OUT=$step" \
      -e "HOLD_RATE=$rate" \
      -e "HOLD_DURATION=$DURATION" \
      load/scripts/holds.ts > "$step/k6.log" 2>&1
  printf 'exit_code=%s\n' "$?" > "$step/exit-code.txt"
  set -e
  # A rate that saturates the service leaves work in flight; let it drain so the next step starts
  # from an idle stack rather than measuring the previous step's backlog.
  sleep 20
done

printf '\n--- invariants ---\n'
./scripts/verify-invariants.sh 2>&1 | tee "$OUT_DIR/verify-invariants.log" | tail -3

python3 - "$OUT_DIR" "$LABEL" "$DURATION" <<'SWEEPJSON'
import glob, json, os, sys

out_dir, label, duration = sys.argv[1:4]
steps = []
for step in sorted(glob.glob(os.path.join(out_dir, 'rate-*'))):
    path = os.path.join(step, 'summary.json')
    if not os.path.exists(path):
        continue
    m = json.load(open(path))['metrics']
    offered = int(os.path.basename(step).split('-')[1])
    def metric(name, key):
        return m.get(name, {}).get(key)
    granted = metric('willcall_holds_granted', 'count') or 0
    refused = metric('willcall_holds_refused', 'count') or 0
    total = metric('http_reqs', 'count') or 0
    # A 503 with Retry-After is the service shedding deliberately; it is neither a grant nor a
    # clean refusal, and calling it a fault would mean reporting the load shedder working as an
    # outage. Anything that is neither granted nor refused is counted here.
    shed = max(total - granted - refused, 0)
    steps.append({
        'offeredRatePerSecond': offered,
        'achievedRatePerSecond': metric('http_reqs', 'rate'),
        'requests': total,
        'granted': granted,
        'refused409': refused,
        'shedOrFailed': shed,
        'shedFraction': round(shed / total, 4) if total else None,
        'droppedIterations': metric('dropped_iterations', 'count') or 0,
        'holdP50Ms': metric('willcall_hold_duration', 'med'),
        'holdP99Ms': metric('willcall_hold_duration', 'p(99)'),
        'holdMaxMs': metric('willcall_hold_duration', 'max'),
        'maxVus': metric('vus_max', 'value'),
    })

# The sustainable rate: the highest step that shed under 1% and kept p99 inside the 150 ms budget.
# Both conditions matter - a step can serve everything slowly, and that is not capacity either.
within = [s for s in steps
          if (s['shedFraction'] or 0) < 0.01 and (s['holdP99Ms'] or 1e9) <= 150]
report = {
    'label': label,
    'stepDuration': duration,
    'replicas': 3,
    'steps': steps,
    'sustainableRatePerSecond': max((s['offeredRatePerSecond'] for s in within), default=None),
    'criterion': 'highest offered rate that shed under 1% of requests and kept hold p99 at or below 150 ms',
}
if report['sustainableRatePerSecond']:
    report['sustainableRatePerReplica'] = report['sustainableRatePerSecond'] / report['replicas']
json.dump(report, open(os.path.join(out_dir, 'capacity-sweep.json'), 'w'), indent=2)

print('')
print(f"{'offered':>8} {'achieved':>9} {'granted':>8} {'409':>7} {'shed':>7} {'shed%':>6} "
      f"{'p50 ms':>7} {'p99 ms':>8}")
for s in steps:
    print(f"{s['offeredRatePerSecond']:>8} {s['achievedRatePerSecond']:>9.0f} {s['granted']:>8} "
          f"{s['refused409']:>7} {s['shedOrFailed']:>7} "
          f"{(s['shedFraction'] or 0) * 100:>5.1f}% {s['holdP50Ms']:>7.0f} {s['holdP99Ms']:>8.0f}")
print('')
print(f"sustainable rate : {report['sustainableRatePerSecond']} requests/s "
      f"({report.get('sustainableRatePerReplica', 0):.0f} per replica)")
print(f"criterion        : {report['criterion']}")
SWEEPJSON

{
  printf '# Run context: capacity sweep\n\n'
  printf '**%s**\n\n' "$LABEL"
  printf '| Field | Value |\n|---|---|\n'
  printf '| Collected (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Offered rates | %s requests/s |\n' "$RATES"
  printf '| Duration per step | %s |\n' "$DURATION"
  printf '| Application | 3 replicas, 2 vCPU / 2 GiB each |\n'
  printf '| Load generator | k6 on the same host, competing for CPU |\n'
} > "$OUT_DIR/run-context.md"

printf '\nwritten to %s\n' "$OUT_DIR"
