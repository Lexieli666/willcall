#!/usr/bin/env bash
# Block until the stack answers /ready, or fail loudly. Used by `make up` and by CI so a load
# test never starts against a half-warm JVM and then reports the warm-up as latency.
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:8080}"
TIMEOUT_SECONDS="${2:-180}"

printf 'waiting for %s/ready (timeout %ss)\n' "$BASE_URL" "$TIMEOUT_SECONDS"
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))

while :; do
  if body=$(curl -fsS --max-time 3 "$BASE_URL/ready" 2>/dev/null); then
    printf 'ready: %s\n' "$body"
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    printf 'FAILED: %s/ready did not answer within %ss\n' "$BASE_URL" "$TIMEOUT_SECONDS" >&2
    docker compose ps || true
    exit 1
  fi
  sleep 2
done

# A first request that compiles the hot path should not be counted as user-visible latency.
for _ in $(seq 1 20); do curl -fsS --max-time 3 "$BASE_URL/api/system/status" >/dev/null || true; done
printf 'warm-up requests done\n'
