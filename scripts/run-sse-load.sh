#!/usr/bin/env bash
# Hold N Server-Sent Events connections open and measure how long a seat change takes to reach
# them, from the database commit.
#
#   ./scripts/run-sse-load.sh 5000
#
# Writes to load/results/<date>/sse-<count>-<time>/ with the same run-context.md every other
# result directory has, plus the server's memory before and during so the per-connection figure
# has something behind it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CONNECTIONS="${1:-5000}"
BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}"
HOLD_SECONDS="${WILLCALL_SSE_HOLD_SECONDS:-60}"
RAMP_MS="${WILLCALL_SSE_RAMP_MS:-20000}"

DATE="$(date -u +%Y-%m-%d)"
STAMP="$(date -u +%H%M%S)"
OUT_DIR="load/results/${DATE}/sse-${CONNECTIONS}-${STAMP}"
mkdir -p "$OUT_DIR"

app_memory_bytes() {
  # The resident set of every application replica, summed. docker stats reports a human string,
  # so the numbers come from the cgroup files the daemon reads, via `docker stats --no-stream`
  # with a machine-readable format.
  docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' 2>/dev/null \
    | awk '/willcall-app[0-9]/ {print $2}' \
    | python3 -c "
import sys, re
total = 0.0
units = {'B': 1, 'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3}
for line in sys.stdin:
    m = re.match(r'([0-9.]+)([A-Za-z]+)', line.strip())
    if m:
        total += float(m.group(1)) * units.get(m.group(2), 1)
print(int(total))
"
}

printf 'connections : %s\n' "$CONNECTIONS"
printf 'target      : %s\n' "$BASE_URL"
printf 'results     : %s\n\n' "$OUT_DIR"

printf 'file descriptor limit: %s\n' "$(ulimit -n)"
if [ "$(ulimit -n)" -lt $((CONNECTIONS * 2)) ]; then
  printf 'WARNING: ulimit -n is %s, which is below twice the connection count. Connections will\n' "$(ulimit -n)"
  printf '         fail with EMFILE, which is the generator hitting a limit and not the service.\n'
  printf '         See docs/load-testing.md.\n\n'
fi

MEM_BEFORE="$(app_memory_bytes)"
printf 'application resident memory before: %s bytes\n' "$MEM_BEFORE"

# Sampled while the connections are open, in the background, so the peak is captured rather than
# whatever happens to be true when the run ends.
MEM_SAMPLES="$OUT_DIR/memory-samples.txt"
: > "$MEM_SAMPLES"
(
  while :; do
    printf '%s %s\n' "$(date -u +%s)" "$(app_memory_bytes)" >> "$MEM_SAMPLES"
    sleep 5
  done
) &
SAMPLER_PID=$!
trap 'kill "$SAMPLER_PID" 2>/dev/null || true' EXIT

# Compiled rather than run from source: Node 20 has no --experimental-strip-types, and adding a
# TypeScript runtime loader would put a dependency between the measurement and the thing measured.
( cd load/sse && npx tsc )

set +e
BASE_URL="$BASE_URL" \
CONNECTIONS="$CONNECTIONS" \
RAMP_MS="$RAMP_MS" \
HOLD_SECONDS="$HOLD_SECONDS" \
OUT="$OUT_DIR/sse-result.json" \
  node load/sse/dist/main.js 2>&1 | tee "$OUT_DIR/stdout.log"
RUN_EXIT=${PIPESTATUS[0]}
set -e

kill "$SAMPLER_PID" 2>/dev/null || true
trap - EXIT

MEM_PEAK="$(awk '{ if ($2 > max) max = $2 } END { print max + 0 }' "$MEM_SAMPLES")"
printf 'application resident memory peak  : %s bytes\n' "$MEM_PEAK"

python3 - "$OUT_DIR" "$MEM_BEFORE" "$MEM_PEAK" <<'PY'
import json, os, sys

out_dir, before, peak = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
path = os.path.join(out_dir, 'sse-result.json')
if not os.path.exists(path):
    raise SystemExit('no result file; the run failed before writing one')

result = json.load(open(path))
established = result.get('establishedConnections', 0)
delta = peak - before

result['memory'] = {
    'applicationResidentBeforeBytes': before,
    'applicationResidentPeakBytes': peak,
    'differenceBytes': delta,
    'perConnectionBytes': round(delta / established) if established else None,
    'method': (
        'Sum of the resident set of every application replica, sampled every five seconds while '
        'the connections were open, minus the same figure taken before they were opened, divided '
        'by the number established. This attributes all growth during the window to the '
        'connections, which slightly overstates them: JIT compilation and heap growth from the '
        'seat-change traffic land in the same figure. It is an upper bound, and is reported as '
        'one rather than as a precise cost.'
    ),
}
json.dump(result, open(path, 'w'), indent=2)

per = result['memory']['perConnectionBytes']
if per is not None:
    print(f"memory per connection (upper bound): {per} bytes ({per / 1024:.1f} KiB)")
PY

{
  printf '# Run context: %s SSE connections\n\n' "$CONNECTIONS"
  printf '**local Docker Compose, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Started (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Target | `%s` |\n' "$BASE_URL"
  printf '| Connections requested | %s |\n' "$CONNECTIONS"
  printf '| Ramp | %s ms |\n' "$RAMP_MS"
  printf '| Hold | %s s |\n' "$HOLD_SECONDS"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Application replicas | %s |\n' "$(docker ps --filter 'label=com.docker.compose.project=willcall' --format '{{.Names}}' | grep -c 'app[0-9]' || echo 0)"
  printf '| Host kernel | %s |\n' "$(uname -sr)"
  printf '| Host logical cores | %s |\n' "$(nproc)"
  printf '| Host memory | %s |\n' "$(free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo unknown)"
  printf '| Generator | Node %s, same host as the service |\n' "$(node -v)"
  printf '| `ulimit -n` | %s |\n' "$(ulimit -n)"
  printf '| `ip_local_port_range` | %s |\n' "$(tr '\t' ' ' < /proc/sys/net/ipv4/ip_local_port_range 2>/dev/null || echo unknown)"
  printf '| `somaxconn` | %s |\n' "$(cat /proc/sys/net/core/somaxconn 2>/dev/null || echo unknown)"
  printf '| `tcp_max_syn_backlog` | %s |\n' "$(cat /proc/sys/net/ipv4/tcp_max_syn_backlog 2>/dev/null || echo unknown)"
  printf '| Coalescing window | 50 ms, included in every propagation figure below |\n'
  printf '\n## Caveats\n\n'
  printf -- '- Propagation is measured from the PostgreSQL commit to the frame arriving at the client, and **includes** the 50 ms coalescing window and the outbox relay tick. Both are time a buyer waits; excluding them would make the number smaller and less true.\n'
  printf -- '- Generator and service share a host, so their clocks are the same clock and the subtraction is exact. Across machines this measurement would need NTP-quality time or a round-trip estimate instead.\n'
  printf -- '- Memory per connection is an upper bound: it attributes all growth during the window to the connections, including JIT compilation and heap growth caused by the seat-change traffic.\n'
  printf -- '- The generator competes with the service for CPU on the same host.\n'
} > "$OUT_DIR/run-context.md"

printf '\nwritten to %s\n' "$OUT_DIR"
exit "$RUN_EXIT"
