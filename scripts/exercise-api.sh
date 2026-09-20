#!/usr/bin/env bash
# Drive real traffic through the reservation core so that verify-invariants.sh has something
# to check. A clean database trivially satisfies every invariant, which would make the CI job
# a test of nothing.
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:18081}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

req() {
  local method="$1" path="$2" body="${3:-}" extra_header="${4:-}"
  local args=(-sS -X "$method" -H 'Content-Type: application/json' -w '\n%{http_code}')
  [ -n "$extra_header" ] && args+=(-H "$extra_header")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}" "$BASE_URL$path"
}

printf 'exercising %s\n' "$BASE_URL"
curl -fsS "$BASE_URL/api/system/status" && printf '\n'
