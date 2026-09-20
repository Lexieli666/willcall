#!/usr/bin/env bash
# Copy a completed JMH run into load/results/ with its context, and derive the crossover table
# from the raw JSON rather than from anybody's reading of it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RAW="server/build/results/jmh/results.json"
[ -f "$RAW" ] || { printf 'no JMH results at %s; run `make bench` first\n' "$RAW" >&2; exit 1; }

DATE="$(date -u +%Y-%m-%d)"
OUT_DIR="load/results/${DATE}/jmh-contiguous-search"
mkdir -p "$OUT_DIR"
cp "$RAW" "$OUT_DIR/results.json"

python3 - "$OUT_DIR" <<'PY'
import json, sys
from collections import defaultdict

out_dir = sys.argv[1]
data = json.load(open(f'{out_dir}/results.json'))

scores = defaultdict(dict)
for entry in data:
    name = entry['benchmark'].rsplit('.', 1)[-1]
    p = entry['params']
    key = (int(p['seatsPerRow']), int(p['occupancyPercent']), int(p['requestedRun']))
    scores[key][name] = (entry['primaryMetric']['score'], entry['primaryMetric']['scoreError'])

lines = []
lines.append('| seats/row | occupancy | seats wanted | linear scan (ns) | segment tree (ns) | winner | ratio |')
lines.append('|---:|---:|---:|---:|---:|---|---:|')
for key in sorted(scores):
    seats, occupancy, wanted = key
    row = scores[key]
    if 'linearScanSearch' not in row or 'segmentTreeSearch' not in row:
        continue
    linear, linear_err = row['linearScanSearch']
    tree, tree_err = row['segmentTreeSearch']
    winner = 'linear scan' if linear < tree else 'segment tree'
    ratio = max(linear, tree) / max(min(linear, tree), 1e-9)
    lines.append(
        f'| {seats:,} | {occupancy}% | {wanted} | {linear:.1f} ± {linear_err:.1f} '
        f'| {tree:.1f} ± {tree_err:.1f} | {winner} | {ratio:.1f}x |')

update = []
update.append('| seats/row | segment tree update (ns) | segment tree build (ns) |')
update.append('|---:|---:|---:|')
for seats in sorted({k[0] for k in scores}):
    keys = [k for k in scores if k[0] == seats and 'segmentTreeUpdate' in scores[k]]
    if not keys:
        continue
    upd = sum(scores[k]['segmentTreeUpdate'][0] for k in keys) / len(keys)
    build = sum(scores[k]['segmentTreeConstruction'][0] for k in keys) / len(keys)
    update.append(f'| {seats:,} | {upd:.0f} | {build:,.0f} |')

with open(f'{out_dir}/crossover.md', 'w') as handle:
    handle.write('# Segment tree against linear scan\n\n')
    handle.write('Derived from `results.json` by `scripts/record-jmh.sh`. Nothing here is typed by hand.\n\n')
    handle.write('## Search: finding the first run of N adjacent free seats\n\n')
    handle.write('\n'.join(lines))
    handle.write('\n\n## The cost the tree pays for that\n\n')
    handle.write('\n'.join(update))
    handle.write('\n')

print('\n'.join(lines))
PY

{
  printf '\n## Run context\n\n'
  printf '**local workstation, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Recorded (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Host kernel | %s |\n' "$(uname -sr)"
  printf '| Host logical cores | %s |\n' "$(nproc)"
  printf '| Host memory | %s |\n' "$(free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo unknown)"
  printf '| JMH | 1.37, 1 fork, 3 warm-up and 5 measurement iterations of 1 s |\n'
  printf '| Seed | fixed at 20260920, so every machine benchmarks the same input |\n'
} >> "load/results/$(date -u +%Y-%m-%d)/jmh-contiguous-search/crossover.md"

printf '\nwritten to %s\n' "$OUT_DIR"
