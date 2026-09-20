#!/usr/bin/env bash
# Run a k6 scenario and write everything needed to audit the result next to it.
#
#   ./scripts/run-load.sh smoke
#   ./scripts/run-load.sh flash --iterations 1
#
# Output goes to load/results/<YYYY-MM-DD>/<scenario>-<HHMMSS>/ and always contains:
#   run-context.md   the commit, the host, the container limits, the target, local or cloud
#   summary.json     k6's end-of-test summary, the file every published percentile comes from
#   stdout.log       the full k6 console output
#   raw.json.gz      per-sample data, when WILLCALL_RAW_SAMPLES=1
#
# The context file is generated from the live environment, never typed. That is what makes
# "local Docker Compose, not AWS" a fact about the run rather than a label somebody remembered
# to apply.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SCENARIO="${1:?usage: run-load.sh <scenario> [k6 args...]}"
shift || true

SCRIPT="load/scripts/${SCENARIO}.ts"
[ -f "$SCRIPT" ] || { printf 'no such scenario: %s\n' "$SCRIPT" >&2; exit 1; }

BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}"
DATE="$(date -u +%Y-%m-%d)"
STAMP="$(date -u +%H%M%S)"
OUT_DIR="load/results/${DATE}/${SCENARIO}-${STAMP}"
mkdir -p "$OUT_DIR"

GIT_COMMIT="$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
GIT_DIRTY="$(git status --porcelain 2>/dev/null | head -1)"

# --------------------------------------------------------------- environment capture

deployment_kind() {
  case "$BASE_URL" in
    *amazonaws.com*|*elb*) echo "AWS" ;;
    *127.0.0.1*|*localhost*) echo "local Docker Compose, not AWS" ;;
    *) echo "unknown target: $BASE_URL" ;;
  esac
}

container_limits() {
  if ! command -v docker >/dev/null 2>&1; then echo "| _docker not available_ | | | |"; return; fi
  docker ps --filter "label=com.docker.compose.project=willcall" \
    --format '{{.Names}}' 2>/dev/null | sort | while read -r name; do
    [ -z "$name" ] && continue
    local_cpus=$(docker inspect "$name" --format '{{.HostConfig.NanoCpus}}' 2>/dev/null || echo 0)
    local_mem=$(docker inspect "$name" --format '{{.HostConfig.Memory}}' 2>/dev/null || echo 0)
    image=$(docker inspect "$name" --format '{{.Config.Image}}' 2>/dev/null || echo unknown)
    cpu_txt=$([ "${local_cpus:-0}" -gt 0 ] 2>/dev/null && python3 -c "print(f'{$local_cpus/1e9:.2f} vCPU')" || echo 'unlimited')
    mem_txt=$([ "${local_mem:-0}" -gt 0 ] 2>/dev/null && python3 -c "print(f'{$local_mem/1024/1024/1024:.2f} GiB')" || echo 'unlimited')
    printf '| `%s` | %s | %s | %s |\n' "$name" "$image" "$cpu_txt" "$mem_txt"
  done
}

replica_count=$(docker ps --filter "label=com.docker.compose.project=willcall" --format '{{.Names}}' 2>/dev/null | grep -c 'app[0-9]' || true)

{
  printf '# Run context: %s\n\n' "$SCENARIO"
  printf '**%s**\n\n' "$(deployment_kind)"
  printf '| Field | Value |\n|---|---|\n'
  printf '| Scenario | `%s` |\n' "$SCRIPT"
  printf '| Started (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Target base URL | `%s` |\n' "$BASE_URL"
  printf '| Git commit | `%s`%s |\n' "$GIT_COMMIT" "$([ -n "$GIT_DIRTY" ] && echo ' (working tree dirty)')"
  printf '| Application replicas | %s |\n' "${replica_count:-0}"
  printf '| Host kernel | %s |\n' "$(uname -sr)"
  printf '| Host logical cores | %s |\n' "$(nproc)"
  printf '| Host memory | %s |\n' "$(free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo unknown)"
  printf '| Load generator | k6 %s, same host as the service |\n' "$(k6 version 2>/dev/null | head -1 | awk '{print $2}')"
  printf '| `ulimit -n` | %s |\n' "$(ulimit -n)"
  printf '| `ip_local_port_range` | %s |\n' "$(cat /proc/sys/net/ipv4/ip_local_port_range 2>/dev/null | tr '\t' ' ' || echo unknown)"
  printf '| `somaxconn` | %s |\n' "$(cat /proc/sys/net/core/somaxconn 2>/dev/null || echo unknown)"
  printf '\n## Container limits at run time\n\n'
  printf '| Container | Image | CPU limit | Memory limit |\n|---|---|---|---|\n'
  container_limits
  printf '\n## Caveats\n\n'
  printf -- '- The load generator shares the host with the service, so generator CPU competes with application CPU. The host core count above is the total available to both.\n'
  printf -- '- Network latency between generator and service is loopback, near zero. Percentiles here are therefore service time plus loopback, not service time plus internet.\n'
  printf -- '- Numbers from this file may be published only with the deployment label at the top of this document attached.\n'
} > "$OUT_DIR/run-context.md"

# --------------------------------------------------------------- run

printf 'scenario   : %s\n' "$SCENARIO"
printf 'target     : %s\n' "$BASE_URL"
printf 'results    : %s\n\n' "$OUT_DIR"

K6_ARGS=(run
  --summary-export "$OUT_DIR/summary.json"
  --summary-trend-stats 'min,med,avg,p(90),p(95),p(99),max'
  -e "BASE_URL=$BASE_URL"
  -e "SCENARIO_OUT=$OUT_DIR"
)

if [ "${WILLCALL_RAW_SAMPLES:-0}" = "1" ]; then
  K6_ARGS+=(--out "json=$OUT_DIR/raw.json")
fi

for extra in "${WILLCALL_K6_ENV[@]:-}"; do
  [ -n "$extra" ] && K6_ARGS+=(-e "$extra")
done

set +e
k6 "${K6_ARGS[@]}" "$@" "$SCRIPT" 2>&1 | tee "$OUT_DIR/stdout.log"
K6_EXIT=${PIPESTATUS[0]}
set -e

[ -f "$OUT_DIR/raw.json" ] && gzip -f "$OUT_DIR/raw.json"

printf '\nk6 exit code: %s\n' "$K6_EXIT" | tee -a "$OUT_DIR/stdout.log"
printf 'exit_code=%s\n' "$K6_EXIT" > "$OUT_DIR/exit-code.txt"

# The invariant is checked after every run that touched the reservation core. A load test that
# passes its thresholds while overselling is a failed run, not a passed one.
if [ "${WILLCALL_SKIP_INVARIANTS:-0}" != "1" ] && [ "$BASE_URL" != "${BASE_URL#http://127.0.0.1}" ]; then
  printf '\n--- verify-invariants ---\n' | tee -a "$OUT_DIR/stdout.log"
  if ./scripts/verify-invariants.sh 2>&1 | tee "$OUT_DIR/verify-invariants.log"; then
    printf 'invariants: PASS\n' | tee -a "$OUT_DIR/stdout.log"
  else
    printf 'invariants: FAIL\n' | tee -a "$OUT_DIR/stdout.log"
    K6_EXIT=1
  fi
fi

exit "$K6_EXIT"
