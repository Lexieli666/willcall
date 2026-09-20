#!/usr/bin/env bash
# Copy the frontend measurements into load/results/ with their context.
#
# Lighthouse scores, Core Web Vitals, the seat-map render time and the bundle size all end up in
# README.md, so each needs a raw file behind it. Everything here is read out of Lighthouse's own
# reports, Playwright's own attachments and Vitest's own coverage summary; no number is passed in.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LABEL="${1:-frontend}"
DATE="$(date -u +%Y-%m-%d)"
OUT_DIR="load/results/${DATE}/${LABEL}"
mkdir -p "$OUT_DIR"

if compgen -G "web/.lighthouseci/lhr-*.json" >/dev/null; then
  cp web/.lighthouseci/lhr-*.json "$OUT_DIR/" 2>/dev/null || true
fi
if compgen -G "web/playwright-report/results.json" >/dev/null; then
  cp web/playwright-report/results.json "$OUT_DIR/playwright-results.json"
fi
if [ -f web/coverage/coverage-summary.json ]; then
  cp web/coverage/coverage-summary.json "$OUT_DIR/vitest-coverage.json"
fi

python3 - "$OUT_DIR" <<'PY'
import glob, json, os, statistics, subprocess, sys

out_dir = sys.argv[1]
report = {
    'commit': subprocess.run(['git', 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip(),
}

runs = []
for path in sorted(glob.glob(f'{out_dir}/lhr-*.json')):
    lhr = json.load(open(path))
    audits = lhr['audits']
    runs.append({
        'url': lhr.get('finalDisplayedUrl') or lhr.get('finalUrl'),
        'categories': {k: v['score'] for k, v in lhr['categories'].items()},
        'largestContentfulPaintMs': audits['largest-contentful-paint']['numericValue'],
        'cumulativeLayoutShift': audits['cumulative-layout-shift']['numericValue'],
        'totalBlockingTimeMs': audits['total-blocking-time']['numericValue'],
        'firstContentfulPaintMs': audits['first-contentful-paint']['numericValue'],
        'speedIndexMs': audits['speed-index']['numericValue'],
    })

if runs:
    def med(key):
        return round(statistics.median(run[key] for run in runs), 4)
    report['lighthouse'] = {
        'runs': len(runs),
        'preset': 'desktop',
        'accessibility': min(run['categories']['accessibility'] for run in runs),
        'performance': min(run['categories']['performance'] for run in runs),
        'bestPractices': min(run['categories']['best-practices'] for run in runs),
        'seo': min(run['categories']['seo'] for run in runs),
        'medianLargestContentfulPaintMs': med('largestContentfulPaintMs'),
        'medianCumulativeLayoutShift': med('cumulativeLayoutShift'),
        'medianTotalBlockingTimeMs': med('totalBlockingTimeMs'),
        'medianFirstContentfulPaintMs': med('firstContentfulPaintMs'),
        'perRun': runs,
    }

# The seat-map render time, read from the Playwright attachment the test writes.
pw_path = f'{out_dir}/playwright-results.json'
if os.path.exists(pw_path):
    pw = json.load(open(pw_path))
    tests = {'total': 0, 'passed': 0, 'failed': 0}

    def walk(suite):
        for spec in suite.get('specs', []):
            for test in spec.get('tests', []):
                tests['total'] += 1
                results = test.get('results', [])
                status = results[-1]['status'] if results else 'unknown'
                if status == 'passed':
                    tests['passed'] += 1
                else:
                    tests['failed'] += 1
                for result in results:
                    for attachment in result.get('attachments', []):
                        if attachment.get('name') != 'seatmap-render-ms.json':
                            continue
                        # Playwright inlines a body attachment as base64 and writes a path only
                        # for file attachments, so both shapes have to be handled; reading only
                        # `path` silently produced no measurement at all.
                        try:
                            if attachment.get('body'):
                                import base64
                                report['seatMapRender'] = json.loads(
                                    base64.b64decode(attachment['body']).decode('utf-8'))
                            elif attachment.get('path'):
                                report['seatMapRender'] = json.load(open(attachment['path']))
                        except (OSError, ValueError):
                            pass
        for child in suite.get('suites', []):
            walk(child)

    for suite in pw.get('suites', []):
        walk(suite)
    report['playwright'] = tests

cov_path = f'{out_dir}/vitest-coverage.json'
if os.path.exists(cov_path):
    cov = json.load(open(cov_path))
    total = cov.get('total', {})
    report['vitestCoverage'] = {
        key: total[key]['pct'] for key in ('lines', 'statements', 'functions', 'branches') if key in total
    }

# Gzipped bundle size per route, from the built manifest.
try:
    size = subprocess.run(
        ['node', 'scripts/check-bundle-budget.mjs', 'web/dist'], capture_output=True, text=True)
    report['bundleBudget'] = size.stdout.strip()
except OSError:
    pass

json.dump(report, open(f'{out_dir}/frontend-results.json', 'w'), indent=2)

if 'lighthouse' in report:
    lh = report['lighthouse']
    print(f"lighthouse ({lh['runs']} runs): accessibility {lh['accessibility']}, "
          f"performance {lh['performance']}, LCP {lh['medianLargestContentfulPaintMs']:.0f} ms, "
          f"CLS {lh['medianCumulativeLayoutShift']}, TBT {lh['medianTotalBlockingTimeMs']:.0f} ms")
if 'seatMapRender' in report:
    r = report['seatMapRender']
    print(f"seat map: {r['seats']} seats rendered in {r['renderMs']:.1f} ms (budget {r['budgetMs']} ms)")
if 'playwright' in report:
    print(f"playwright: {report['playwright']['total']} tests, {report['playwright']['failed']} failed")
PY

{
  printf '# Run context: %s\n\n' "$LABEL"
  printf '**local Docker Compose, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Recorded (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Target | %s |\n' "${WILLCALL_LHCI_URL:-http://127.0.0.1:8080/}"
  printf '| Host kernel | %s |\n' "$(uname -sr)"
  printf '| Host logical cores | %s |\n' "$(nproc)"
  printf '| Browser | Chromium via Playwright, headless |\n'
  printf '| Lighthouse preset | desktop, 3 runs, minimum score reported |\n'
  printf '\n## Caveats\n\n'
  printf -- '- Lighthouse ran against the local edge proxy on loopback, so network time is near zero. LCP here is render time plus a negligible transfer, not what a phone on a mobile network would see.\n'
  printf -- '- The seat-map render figure is the application'"'"'s own `performance.measure`, from the moment the seat data is available to the frame after the DOM is committed. It excludes the fetch deliberately: it measures building and painting the map, which is what the budget is about.\n'
  printf -- '- Vitest coverage counts unit tests only. The route components are covered by the Playwright suite, which this figure does not see, so the frontend line coverage understates what is exercised.\n'
} > "$OUT_DIR/run-context.md"

printf '\nwritten to %s\n' "$OUT_DIR"
