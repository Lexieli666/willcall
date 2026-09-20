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

# The step duration is passed so goodput is per second rather than per step.
./scripts/summarise_capacity_sweep.py "$OUT_DIR" "${DURATION%s}"

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
