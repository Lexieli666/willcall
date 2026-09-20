#!/usr/bin/env bash
# Run the flash sale N times and report how many of the runs oversold.
#
# One run proves nothing about a race. The claim being made is "zero oversells across N runs", so
# the suite runs it N times, checks the invariant after each, and keeps every raw file. A run that
# oversells is not retried or discarded — it is reported.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RUNS="${1:-50}"
DATE="$(date -u +%Y-%m-%d)"
STAMP="$(date -u +%H%M%S)"
SUITE_DIR="load/results/${DATE}/flash-suite-${STAMP}"
mkdir -p "$SUITE_DIR"

printf 'flash sale suite: %s runs\n' "$RUNS"
printf 'results: %s\n\n' "$SUITE_DIR"

PASSED=0
FAILED=0
OVERSOLD=0

# Each run starts from an empty catalogue.
#
# Without this the database grows by five thousand seats and several thousand holds per run, and
# by run fifty the sell-out time is dominated by table size rather than by contention — the first
# attempt at this suite drifted from eleven seconds to thirty-one across eight runs. Fifty runs
# that are not comparable measure the accumulation, not the flash sale.
#
# Database growth under a long-lived catalogue is a real concern; it is measured by the seeded
# million-row dataset and its query plans, which is the right instrument for it.
reset_database() {
  if [ "${WILLCALL_FLASH_KEEP_DATA:-0}" = "1" ]; then return; fi
  docker exec willcall-postgres-1 psql -U willcall -d willcall -q -c "
    truncate table outbox, idempotency_records, order_lines, orders, holds, hold_groups,
                   admissions, seats, seat_rows, sections, price_tiers, events, venues
    restart identity cascade;" >/dev/null 2>&1 || true
}

for run in $(seq 1 "$RUNS"); do
  printf '=== run %s/%s ===\n' "$run" "$RUNS"
  reset_database
  RUN_DIR="$SUITE_DIR/run-$(printf '%03d' "$run")"
  mkdir -p "$RUN_DIR"

  set +e
  WILLCALL_BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}" \
  SCENARIO_OUT="$RUN_DIR" \
    k6 run \
      --summary-export "$RUN_DIR/summary.json" \
      --summary-trend-stats 'min,med,avg,p(90),p(95),p(99),max' \
      -e "BASE_URL=${WILLCALL_BASE_URL:-http://127.0.0.1:8080}" \
      -e "SCENARIO_OUT=$RUN_DIR" \
      -e "FLASH_SEATS=${FLASH_SEATS:-5000}" \
      -e "FLASH_BUYERS=${FLASH_BUYERS:-10000}" \
      -e "FLASH_WINDOW=${FLASH_WINDOW:-10s}" \
      load/scripts/flash.ts > "$RUN_DIR/stdout.log" 2>&1
  K6_EXIT=$?
  set -e

  # The invariant is checked against the database, not inferred from the run's own output.
  set +e
  ./scripts/verify-invariants.sh > "$RUN_DIR/verify-invariants.log" 2>&1
  INVARIANT_EXIT=$?
  set -e

  SOLD_OUT_LINE="$(grep -o 'final tally[^"]*' "$RUN_DIR/stdout.log" | head -1 || true)"

  if [ "$K6_EXIT" -eq 0 ] && [ "$INVARIANT_EXIT" -eq 0 ]; then
    PASSED=$((PASSED + 1))
    printf 'run %s: PASS  %s\n' "$run" "$SOLD_OUT_LINE"
  else
    FAILED=$((FAILED + 1))
    [ "$INVARIANT_EXIT" -ne 0 ] && OVERSOLD=$((OVERSOLD + 1))
    printf 'run %s: FAIL (k6 %s, invariants %s)  %s\n' "$run" "$K6_EXIT" "$INVARIANT_EXIT" "$SOLD_OUT_LINE"
  fi

  printf '%s %s %s\n' "$run" "$K6_EXIT" "$INVARIANT_EXIT" >> "$SUITE_DIR/outcomes.txt"
done

python3 - "$SUITE_DIR" "$RUNS" <<'PY'
import glob, json, os, re, statistics, subprocess, sys

suite_dir, runs = sys.argv[1], int(sys.argv[2])
per_run = []

# run-[0-9][0-9][0-9] rather than run-*: run-context.md sits beside the run directories.
for run_dir in sorted(glob.glob(f'{suite_dir}/run-[0-9][0-9][0-9]')):
    entry = {'run': os.path.basename(run_dir)}
    summary_path = os.path.join(run_dir, 'summary.json')
    if os.path.exists(summary_path):
        summary = json.load(open(summary_path))
        metrics = summary.get('metrics', {})

        def count(name):
            return metrics.get(name, {}).get('count', 0)

        def trend(name, stat):
            return metrics.get(name, {}).get(stat)

        entry['granted'] = count('willcall_flash_granted')
        entry['refused'] = count('willcall_flash_refused')
        entry['confirmed'] = count('willcall_flash_confirmed')
        entry['serverErrors'] = count('willcall_flash_server_errors')
        entry['holdP99Ms'] = trend('willcall_flash_hold_duration', 'p(99)')
        entry['confirmP99Ms'] = trend('willcall_flash_confirm_duration', 'p(99)')
        entry['durationMs'] = summary.get('state', {}).get('testRunDurationMs')

    stdout_path = os.path.join(run_dir, 'stdout.log')
    if os.path.exists(stdout_path):
        text = open(stdout_path, errors='replace').read()
        tally = re.search(r'(\d+) sold, (\d+) held, (\d+) available', text)
        if tally:
            entry['sold'] = int(tally.group(1))
            entry['held'] = int(tally.group(2))
            entry['available'] = int(tally.group(3))

    invariant_path = os.path.join(run_dir, 'verify-invariants.log')
    entry['invariantsHeld'] = (
        os.path.exists(invariant_path)
        and 'all invariant checks passed' in open(invariant_path, errors='replace').read()
    )
    per_run.append(entry)

def collect(key):
    return [r[key] for r in per_run if isinstance(r.get(key), (int, float))]

hold_p99 = collect('holdP99Ms')
sold = collect('sold')
durations = collect('durationMs')

report = {
    'runs': runs,
    'commit': subprocess.run(['git', 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip(),
    'runsWithInvariantsHeld': sum(1 for r in per_run if r.get('invariantsHeld')),
    'runsWithServerErrors': sum(1 for r in per_run if r.get('serverErrors', 0) > 0),
    'totalServerErrors': sum(r.get('serverErrors', 0) for r in per_run),
    'oversells': sum(0 if r.get('invariantsHeld') else 1 for r in per_run),
    'holdP99Ms': {
        'min': min(hold_p99) if hold_p99 else None,
        'median': statistics.median(hold_p99) if hold_p99 else None,
        'max': max(hold_p99) if hold_p99 else None,
    },
    'seatsSold': {
        'min': min(sold) if sold else None,
        'median': statistics.median(sold) if sold else None,
        'max': max(sold) if sold else None,
    },
    'runDurationMs': {
        'min': min(durations) if durations else None,
        'median': statistics.median(durations) if durations else None,
        'max': max(durations) if durations else None,
    },
    'perRun': per_run,
}

json.dump(report, open(f'{suite_dir}/flash-suite.json', 'w'), indent=2)

print('')
print(f"runs                      : {report['runs']}")
print(f"invariants held           : {report['runsWithInvariantsHeld']}/{report['runs']}")
print(f"oversells                 : {report['oversells']}")
print(f"runs with a 5xx           : {report['runsWithServerErrors']} ({report['totalServerErrors']} total)")
if hold_p99:
    print(f"hold p99 across runs (ms) : min {report['holdP99Ms']['min']:.0f}, "
          f"median {report['holdP99Ms']['median']:.0f}, max {report['holdP99Ms']['max']:.0f}")
if sold:
    print(f"seats sold per run        : min {report['seatsSold']['min']}, "
          f"median {report['seatsSold']['median']}, max {report['seatsSold']['max']}")
PY

{
  printf '# Run context: flash-sale suite, %s runs\n\n' "$RUNS"
  printf '**local Docker Compose, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Started (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Runs | %s |\n' "$RUNS"
  printf '| Buyers per run | %s arriving within %s |\n' "${FLASH_BUYERS:-10000}" "${FLASH_WINDOW:-10s}"
  printf '| Seats per run | %s |\n' "${FLASH_SEATS:-5000}"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Application replicas | %s |\n' "$(docker ps --filter 'label=com.docker.compose.project=willcall' --format '{{.Names}}' | grep -c 'app[0-9]' || echo 0)"
  printf '| Host logical cores | %s |\n' "$(nproc)"
  printf '| k6 | %s, same host as the service |\n' "$(k6 version 2>/dev/null | head -1 | awk '{print $2}')"
  printf '\n## Caveats\n\n'
  printf -- '- The catalogue is truncated before each run, so the fifty runs are comparable. Without it the database grows by five thousand seats a run and the sell-out time drifts with table size rather than with contention - measured at eleven seconds on run one and thirty-one by run eight. Growth under a long-lived catalogue is measured separately, by the seeded million-row dataset and its query plans.\n'
  printf -- '- Runs contend for CPU with the generator, which shares the host.\n'
  printf -- '- The invariant is checked against the database after every run, not inferred from response codes. A run whose invariant check fails is counted as an oversell and is kept, not retried.\n'
  printf -- '- A 409 is a correct answer under a sell-out and is not counted as an error. Only 5xx and transport failures are.\n'
} > "$SUITE_DIR/run-context.md"

printf '\nwritten to %s\n' "$SUITE_DIR"
[ "$FAILED" -eq 0 ]
