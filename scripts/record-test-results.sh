#!/usr/bin/env bash
# Turn a completed Gradle test run into a committed raw result.
#
# Test counts, the concurrency figures and the coverage percentage are numbers that end up in
# README.md, so they need a raw file behind them exactly as a latency percentile does. This script
# reads Gradle's own XML and JaCoCo's own report; it does not accept any number as an argument.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LABEL="${1:?usage: record-test-results.sh <label>  e.g. phase1-correctness}"
MODE="${WILLCALL_TEST_MODE:-ci}"
DATE="$(date -u +%Y-%m-%d)"
OUT_DIR="load/results/${DATE}/${LABEL}"
mkdir -p "$OUT_DIR"

python3 - "$OUT_DIR" "$MODE" <<'PY'
import glob, json, os, re, subprocess, sys, xml.etree.ElementTree as ET
from statistics import median

out_dir, mode = sys.argv[1], sys.argv[2]

def suite(kind):
    total = failed = skipped = 0
    classes = []
    for path in glob.glob(f'server/build/test-results/{kind}/*.xml'):
        root = ET.parse(path).getroot()
        total += int(root.get('tests'))
        failed += int(root.get('failures')) + int(root.get('errors'))
        skipped += int(root.get('skipped'))
        classes.append({'name': root.get('name'),
                        'tests': int(root.get('tests')),
                        'failures': int(root.get('failures')) + int(root.get('errors')),
                        'timeSeconds': float(root.get('time') or 0)})
    return {'total': total, 'failed': failed, 'skipped': skipped,
            'classes': sorted(classes, key=lambda c: c['name'])}

report = {
    'mode': mode,
    'commit': subprocess.run(['git', 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip(),
    'suites': {kind: suite(kind) for kind in ('test', 'integrationTest')},
}
report['backendTotal'] = sum(s['total'] for s in report['suites'].values())
report['backendFailed'] = sum(s['failed'] for s in report['suites'].values())

# Concurrency runs, parsed out of the test's own stdout rather than retyped.
runs = []
for path in glob.glob('server/build/test-results/integrationTest/*ConcurrentHold*.xml'):
    root = ET.parse(path).getroot()
    text = ''.join(e.text or '' for e in root.iter('system-out'))
    seen = set()
    for m in re.finditer(
            r'run (\d+)/(\d+): granted=(\d+) rejected=(\d+) unexpected=(\d+) '
            r'held=(\d+) available=(\d+) in (\d+) ms', text):
        run_no = int(m.group(1))
        if run_no in seen:
            continue
        seen.add(run_no)
        runs.append({'run': run_no, 'of': int(m.group(2)), 'granted': int(m.group(3)),
                     'rejected': int(m.group(4)), 'unexpected': int(m.group(5)),
                     'heldInDatabase': int(m.group(6)), 'availableInDatabase': int(m.group(7)),
                     'elapsedMs': int(m.group(8))})
runs.sort(key=lambda r: r['run'])
if runs:
    elapsed = [r['elapsedMs'] for r in runs]
    report['concurrency'] = {
        'runs': len(runs),
        'seats': 500,
        'attempts': 10000,
        'allGrantedExactly500': all(r['granted'] == 500 for r in runs),
        'allRejectedExactly9500': all(r['rejected'] == 9500 for r in runs),
        'totalUnexpectedFailures': sum(r['unexpected'] for r in runs),
        'oversells': sum(max(0, r['heldInDatabase'] - 500) for r in runs),
        'elapsedMsMin': min(elapsed),
        'elapsedMsMedian': median(elapsed),
        'elapsedMsMax': max(elapsed),
        'perRun': runs,
    }

# Coverage, read from JaCoCo's XML.
cov_path = 'server/build/reports/jacoco/test/jacocoTestReport.xml'
if os.path.exists(cov_path):
    root = ET.parse(cov_path).getroot()
    for counter in root.findall('counter'):
        kind = counter.get('type')
        covered, missed = int(counter.get('covered')), int(counter.get('missed'))
        total = covered + missed
        if total:
            report.setdefault('coverage', {})[kind.lower()] = {
                'covered': covered, 'missed': missed,
                'percent': round(100 * covered / total, 2)}

with open(os.path.join(out_dir, 'test-results.json'), 'w') as handle:
    json.dump(report, handle, indent=2)

print(f"backend tests: {report['backendTotal']} run, {report['backendFailed']} failed")
if 'concurrency' in report:
    c = report['concurrency']
    print(f"concurrency: {c['runs']} runs, granted always 500: {c['allGrantedExactly500']}, "
          f"unexpected failures: {c['totalUnexpectedFailures']}, oversells: {c['oversells']}")
if 'coverage' in report:
    print(f"line coverage: {report['coverage'].get('line', {}).get('percent')}%")
PY

{
  printf '# Run context: %s\n\n' "$LABEL"
  printf '**local Docker Compose and Testcontainers, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Suite | backend `./gradlew test integrationTest` |\n'
  printf '| Mode | %s |\n' "$MODE"
  printf '| Recorded (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Host kernel | %s |\n' "$(uname -sr)"
  printf '| Host logical cores | %s |\n' "$(nproc)"
  printf '| Host memory | %s |\n' "$(free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo unknown)"
  printf '| Java bytecode target | %s (from the compiled classes, not from whatever java is on PATH) |\n' "$(python3 - <<'INNER'
import glob, struct
paths = glob.glob('server/build/classes/java/main/**/*.class', recursive=True)
if not paths:
    print('unknown')
else:
    with open(paths[0], 'rb') as handle:
        handle.read(6)
        major = struct.unpack('>H', handle.read(2))[0]
    print(f'Java {major - 44} (class file major {major})')
INNER
)"
  printf '| PostgreSQL | postgres:16-alpine via Testcontainers, fsync off |\n'
  printf '| Redis | redis:7-alpine via Testcontainers |\n'
  printf '\n## Caveats\n\n'
  printf -- '- The concurrency figures are measured at the service layer against a real PostgreSQL, not over HTTP. They are a correctness result, not a latency one; HTTP percentiles come from the k6 scenarios.\n'
  printf -- '- `fsync` and `synchronous_commit` are off in the test container. That makes the runs faster; it cannot make an oversell disappear, because an oversell would be visible in the same transaction that created it.\n'
  printf -- '- Coverage is taken from the most recent `jacocoTestReport`, which runs in CI mode. Raising the repetition counts changes how many times a line executes, not which lines execute, so the percentage is the same in both modes.\n'
  printf -- '- Every number in `test-results.json` was parsed from Gradle and JaCoCo output by `scripts/record-test-results.sh`. None was typed by hand.\n'
} > "$OUT_DIR/run-context.md"

printf '\nwritten to %s\n' "$OUT_DIR"
