#!/usr/bin/env bash
# Every verification this project claims, in one command, with a pass/fail line per check.
#
# It exists because "the checks pass" is a claim, and a claim needs something a reader can run. It
# is also what makes a clean-checkout verification possible: every command here is one a new clone
# can execute after `make up`.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}"

# Gradle 8.14 will not run on a Java newer than 24, and this host's default is 25. Resolving it
# here rather than asking the reader to export it means `make verify` works from a clean clone,
# which is the whole claim this script exists to support.
JAVA_HOME="$(./scripts/java-home.sh)"
export JAVA_HOME
printf 'using JAVA_HOME=%s\n' "$JAVA_HOME"

PASSED=0
FAILED=0
RESULTS=()

run_check() {
  local name="$1"
  shift
  printf '\n=== %s ===\n' "$name"
  if "$@"; then
    PASSED=$((PASSED + 1))
    RESULTS+=("PASS  $name")
    printf '--- PASS: %s\n' "$name"
  else
    FAILED=$((FAILED + 1))
    RESULTS+=("FAIL  $name")
    printf '--- FAIL: %s\n' "$name"
  fi
}

run_check 'repository hygiene' ./scripts/check-no-secrets.sh
run_check 'documentation links' ./scripts/check-links.sh
run_check 'backend static checks' bash -c 'cd server && ./gradlew --quiet spotlessCheck compileJava'
run_check 'backend unit and property tests' bash -c 'cd server && ./gradlew --quiet test'
run_check 'backend integration tests' bash -c 'cd server && ./gradlew --quiet integrationTest'
run_check 'backend coverage floor' bash -c \
  'cd server && ./gradlew --quiet jacocoTestReport && ../scripts/check-coverage.sh build/reports/jacoco/test/jacocoTestReport.xml'
run_check 'frontend typecheck' bash -c 'cd web && npx tsc -b'
run_check 'frontend lint' bash -c 'cd web && npm run --silent lint'
run_check 'frontend unit tests' bash -c 'cd web && npm run --silent test'
run_check 'frontend build and bundle budget' bash -c \
  'cd web && npm run --silent build >/dev/null && node ../scripts/check-bundle-budget.mjs dist'
run_check 'k6 scenarios typecheck' bash -c 'cd load && npx tsc --noEmit'
run_check 'SSE generator typecheck' bash -c 'cd load/sse && npx tsc --noEmit'
run_check 'terraform fmt and validate' bash -c \
  'cd infra/terraform && terraform init -backend=false -input=false >/dev/null && terraform fmt -check -recursive && terraform validate >/dev/null'
run_check 'docker compose config' docker compose config -q
run_check 'stack is answering' ./scripts/wait-for-healthy.sh "$BASE_URL" 60
run_check 'drive traffic through the reservation core' ./scripts/exercise-api.sh "$BASE_URL"
run_check 'invariants hold' ./scripts/verify-invariants.sh
run_check 'end-to-end, accessibility and keyboard suites' bash -c \
  "cd web && WILLCALL_E2E_BASE_URL='$BASE_URL' WILLCALL_API_ORIGIN='$BASE_URL' npx playwright test"
run_check 'lighthouse budgets' bash -c "WILLCALL_LHCI_URL='$BASE_URL/' ./scripts/lighthouse.sh"

printf '\n================================================================\n'
for line in "${RESULTS[@]}"; do printf '%s\n' "$line"; done
printf '================================================================\n'
printf '%s passed, %s failed\n' "$PASSED" "$FAILED"

[ "$FAILED" -eq 0 ]
