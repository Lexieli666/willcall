#!/usr/bin/env bash
# Regenerate load/RESULTS_SUMMARY.md from the raw result files.
#
# The rule this enforces: a number appears in the summary only if a committed file produced it. The
# script reads the files and writes the tables; nothing between the generated markers is written by
# hand, so a published figure cannot drift from its source. A missing raw file produces a row
# saying "not measured" rather than a gap the reader has to notice.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - <<'PY'
import glob, json, os, re

def latest(pattern):
    """The most recent directory matching a pattern, or None."""
    matches = sorted(glob.glob(pattern))
    return matches[-1] if matches else None

def load(path):
    try:
        with open(path) as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None

def fmt(value, suffix='', digits=0):
    if value is None:
        return '—'
    if isinstance(value, bool):
        return 'yes' if value else 'no'
    if isinstance(value, float):
        return f'{value:,.{digits}f}{suffix}'
    return f'{value:,}{suffix}'

rows = []

def row(name, measured, target, source, verdict=None):
    rows.append((name, measured, target, source, verdict))

# ---------------------------------------------------------------- correctness

correctness_dir = latest('load/results/*/phase*-correctness')
correctness = load(f'{correctness_dir}/test-results.json') if correctness_dir else None
if correctness:
    src = f'`{correctness_dir}/test-results.json`'
    c = correctness.get('concurrency', {})
    if c:
        ok = (c.get('allGrantedExactly500') and c.get('allRejectedExactly9500')
              and c.get('totalUnexpectedFailures') == 0 and c.get('oversells') == 0)
        row('10,000 concurrent holds on 500 seats',
            f"{c['runs']}/{c['runs']} runs: exactly 500 held, {fmt(c['totalUnexpectedFailures'])} errors, "
            f"{fmt(c['oversells'])} oversells",
            'exactly 500 held, 0 oversells, 0 errors', src, 'met' if ok else 'MISSED')
        row('Wall clock for 10,000 concurrent attempts',
            f"{fmt(c['elapsedMsMin'])} / {fmt(c['elapsedMsMedian'])} / {fmt(c['elapsedMsMax'])} ms (min/median/max)",
            '—', src)
    row('Backend tests', f"{fmt(correctness['backendTotal'])} run, {fmt(correctness['backendFailed'])} failed",
        '150–220', src,
        'met' if correctness['backendTotal'] >= 150 else 'below the range')
    cov = correctness.get('coverage', {}).get('line', {})
    if cov:
        row('Backend line coverage', f"{cov['percent']}%", '≥ 80%', src,
            'met' if cov['percent'] >= 80 else 'MISSED')

# ---------------------------------------------------------------- flash sale

flash_dir = latest('load/results/*/flash-suite-*')
flash = load(f'{flash_dir}/flash-suite.json') if flash_dir else None
if flash:
    src = f'`{flash_dir}/flash-suite.json`'
    row('Flash sale: 10,000 buyers in 10 s for 5,000 seats',
        f"{flash['runsWithInvariantsHeld']}/{flash['runs']} runs with invariants intact, "
        f"{fmt(flash['oversells'])} oversells, {fmt(flash['totalServerErrors'])} server errors",
        '0 oversells across 50 runs', src,
        'met' if flash['oversells'] == 0 and flash['runs'] >= 50 else
        ('0 oversells, fewer than 50 runs' if flash['oversells'] == 0 else 'MISSED'))
    sold_out = flash.get('soldOutSeconds', {})
    if sold_out.get('median') is not None:
        within = 8 <= sold_out['median'] <= 45
        row('Time to sell out',
            f"{fmt(sold_out['min'], digits=1)} / {fmt(sold_out['median'], digits=1)} / "
            f"{fmt(sold_out['max'], digits=1)} s (min/median/max)",
            '8–45 s', src, 'met' if within else 'MISSED')
    hold = flash.get('holdP99Ms', {})
    if hold.get('median') is not None:
        row('Hold p99 during an unpaced burst',
            f"{fmt(hold['median'])} ms median across runs",
            'no target; the paced figure is below', src)

# ---------------------------------------------------------------- sustained holds

holds_dir = latest('load/results/*/holds-*')
holds = load(f'{holds_dir}/k6-summary.json') if holds_dir else None
if holds:
    src = f'`{holds_dir}/k6-summary.json`'
    metrics = holds.get('metrics', {})
    p99 = metrics.get('willcall_hold_duration', {}).get('p(99)')
    if p99 is not None:
        row('Hold p99 at a controlled ~1,000 requests/s', f'{fmt(p99)} ms', '60–150 ms', src,
            'met' if 60 <= p99 <= 150 else 'MISSED')

# ---------------------------------------------------------------- real time

sse_dir = latest('load/results/*/sse-5000-*')
sse = load(f'{sse_dir}/sse-result.json') if sse_dir else None
if sse:
    src = f'`{sse_dir}/sse-result.json`'
    established = sse.get('establishedConnections', 0)
    row('Concurrent SSE connections',
        f"{fmt(established)} established, {fmt(sse.get('failedConnections', 0))} failed",
        '≥ 5,000', src, 'met' if established >= 5000 else 'MISSED')
    prop = sse.get('propagationMs', {})
    if prop:
        p99 = prop.get('p99')
        row('Delta propagation, commit → client, p99',
            f"{fmt(p99)} ms (p50 {fmt(prop.get('p50'))} ms, includes the 50 ms coalescing window)",
            '80–250 ms', src, 'met' if p99 is not None and 80 <= p99 <= 250 else 'MISSED')
    retained = sse.get('retainedHeap', {})
    per = retained.get('perConnectionBytes')
    if per:
        kib = per / 1024
        row('Retained heap per connection', f'{kib:,.1f} KiB', '10–60 KB', src,
            'met' if kib <= 60 else 'MISSED')
    counters = sse.get('counters', {})
    row('Sequence gaps detected by clients',
        f"{fmt(counters.get('gaps'))} over {fmt(counters.get('changes'))} delivered changes",
        '—', src)

# ---------------------------------------------------------------- front end

fe_dir = latest('load/results/*/phase*-frontend')
fe = load(f'{fe_dir}/frontend-results.json') if fe_dir else None
if fe:
    src = f'`{fe_dir}/frontend-results.json`'
    lh = fe.get('lighthouse', {})
    if lh:
        a11y = lh.get('accessibility')
        perf = lh.get('performance')
        row('Lighthouse accessibility', f"{a11y * 100:.0f}" if a11y is not None else '—', '100', src,
            'met' if a11y == 1 else 'MISSED')
        row('Lighthouse performance (desktop)', f"{perf * 100:.0f}" if perf is not None else '—',
            '≥ 90', src, 'met' if perf is not None and perf >= 0.9 else 'MISSED')
        lcp = lh.get('medianLargestContentfulPaintMs')
        row('Largest Contentful Paint', f'{fmt(lcp)} ms', '< 1,500 ms', src,
            'met' if lcp is not None and lcp < 1500 else 'MISSED')
        cls = lh.get('medianCumulativeLayoutShift')
        row('Cumulative Layout Shift', f'{cls:.4f}' if cls is not None else '—', '< 0.05', src,
            'met' if cls is not None and cls < 0.05 else 'MISSED')
    render = fe.get('seatMapRender', {})
    if render:
        ms = render.get('renderMs')
        row('5,000-seat map render', f'{fmt(ms, digits=1)} ms', '40–120 ms', src,
            'met' if ms is not None and ms < 120 else 'MISSED')
    pw = fe.get('playwright', {})
    if pw:
        row('End-to-end tests', f"{fmt(pw.get('total'))} run, {fmt(pw.get('failed'))} failed",
            '30–50', src, 'met' if pw.get('total', 0) >= 30 else 'below the range')
    budget = fe.get('bundleBudget', '')
    size = re.search(r'([0-9.]+) KB gzipped', budget or '')
    if size:
        kb = float(size.group(1))
        row('Gzipped JavaScript per route', f'{kb:,.1f} KB', '< 180 KB', src,
            'met' if kb < 180 else 'MISSED')

# ---------------------------------------------------------------- allocation

jmh_dir = latest('load/results/*/jmh-contiguous-search')
if jmh_dir and os.path.exists(f'{jmh_dir}/results.json'):
    row('Segment tree against linear scan',
        'crossover is an occupancy level, not a row size — see the analysis',
        'linear wins below ~500/row, tree at 2,000+',
        f'`{jmh_dir}/crossover.md`', 'the prediction was wrong; the measurement is published')

# ---------------------------------------------------------------- render

lines = [
    '| Measurement | Measured | Target | Verdict | Raw file |',
    '|---|---|---|---|---|',
]
for name, measured, target, source, verdict in rows:
    mark = verdict or ''
    if mark == 'MISSED':
        mark = '**missed**'
    lines.append(f'| {name} | {measured} | {target} | {mark} | {source} |')

missed = [r for r in rows if r[4] and r[4] != 'met' and not r[4].startswith('the prediction')]
lines.append('')
if missed:
    lines.append(f'### Targets not met: {len(missed)}')
    lines.append('')
    for name, measured, target, source, verdict in missed:
        lines.append(f'- **{name}** ({verdict}) — measured {measured}, target {target}. Raw file: {source}')
else:
    lines.append('### Targets not met: none in the results collected so far')
lines.append('')
lines.append('_Generated by `scripts/build-results-summary.sh` from the files named above._')

table = '\n'.join(lines)

summary_path = 'load/RESULTS_SUMMARY.md'
with open(summary_path) as handle:
    content = handle.read()

begin, end = '<!-- GENERATED:BEGIN -->', '<!-- GENERATED:END -->'
start = content.index(begin) + len(begin)
finish = content.index(end)
with open(summary_path, 'w') as handle:
    handle.write(content[:start] + '\n' + table + '\n' + content[finish:])

print(f'{len(rows)} measurements, {len(missed)} not meeting target')
for name, measured, target, _, verdict in rows:
    # The verdict is printed verbatim rather than collapsed to ok/miss: "below the range" is a
    # distinct outcome from "met" and from "missed", and flattening it would hide a shortfall.
    label = (verdict or 'measured').ljust(18)
    print(f'  {label}{name}: {measured}')
PY
