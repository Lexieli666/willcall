#!/usr/bin/env bash
# Screenshot the Grafana dashboard while it has real data on it.
#
# A dashboard screenshot of an idle stack is a picture of some empty axes and proves only that
# Grafana started. This brings the observability profile up, runs load against the service, waits
# long enough for the panels' time window to fill, and captures the dashboard and the panels worth
# reading on their own. The images land in docs/images/ and are committed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

GRAFANA="${WILLCALL_GRAFANA_URL:-http://127.0.0.1:3000}"
LOAD_SECONDS="${WILLCALL_DASHBOARD_LOAD_SECONDS:-180}"
OUT_DIR="docs/images"
mkdir -p "$OUT_DIR"

printf 'bringing up prometheus and grafana\n'
docker compose --profile replicas --profile observability up -d prometheus grafana

printf 'waiting for grafana\n'
for _ in $(seq 1 60); do
  curl -fsS "$GRAFANA/api/health" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "$GRAFANA/api/health" | head -1

printf '\nrunning %ss of load so the panels have something on them\n' "$LOAD_SECONDS"
# SCENARIO_OUT, because the k6 summary helper and the SSE generator both default their output
# directory to "." and this script runs from the repository root. Two runs of it left
# k6-summary.json and sse-result.json tracked at the top level, which the hygiene check refuses -
# the root allow-list exists precisely because tools drop things there. This load exists to put
# pixels on a dashboard; its summary is not a result anybody should cite.
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
WILLCALL_BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}" \
SCENARIO_OUT="$SCRATCH" \
  k6 run --quiet --no-thresholds \
    -e "SCENARIO_OUT=$SCRATCH" \
    -e "BASE_URL=${WILLCALL_BASE_URL:-http://127.0.0.1:8080}" \
    -e "HOLD_RATE=${WILLCALL_DASHBOARD_RATE:-150}" \
    -e "HOLD_DURATION=${LOAD_SECONDS}s" \
    load/scripts/holds.ts > /tmp/dashboard-load.log 2>&1 &
K6_PID=$!

# A second scenario in parallel so the SSE and waiting-room panels are not empty either: a
# dashboard shot where two thirds of the panels say "No data" documents nothing.
( sleep 10
  SCENARIO_OUT="$SCRATCH" node load/sse/dist/main.js --connections "${WILLCALL_DASHBOARD_SSE:-300}" \
    --hold-seconds "$((LOAD_SECONDS - 30))" --out "$SCRATCH" >/tmp/dashboard-sse.log 2>&1 || true ) &
SSE_PID=$!

wait "$K6_PID" || true
wait "$SSE_PID" || true

printf '\ncapturing\n'
export WILLCALL_GRAFANA_URL="$GRAFANA"
export WILLCALL_IMAGE_DIR="$REPO_ROOT/$OUT_DIR"
cd web && npx playwright test --config=playwright.dashboards.config.ts
cd "$REPO_ROOT"

printf '\nimages in %s:\n' "$OUT_DIR"
ls -la "$OUT_DIR"
