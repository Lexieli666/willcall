#!/usr/bin/env bash
# Enforce the backend line-coverage floor from the JaCoCo XML report. The floor is a ratchet:
# raise it when coverage rises, never lower it to make a build green.
set -euo pipefail

REPORT="${1:?usage: check-coverage.sh <jacocoTestReport.xml>}"
FLOOR="${WILLCALL_COVERAGE_FLOOR:-80}"

if [ ! -f "$REPORT" ]; then
  printf 'FAIL: no coverage report at %s\n' "$REPORT" >&2
  exit 1
fi

read -r covered missed <<EOF
$(python3 - "$REPORT" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for counter in root.findall('counter'):
    if counter.get('type') == 'LINE':
        print(counter.get('covered'), counter.get('missed'))
        break
else:
    print(0, 0)
PY
)
EOF

total=$((covered + missed))
if [ "$total" -eq 0 ]; then
  printf 'FAIL: coverage report contains no lines\n' >&2
  exit 1
fi

pct=$(python3 -c "print(f'{100*$covered/$total:.2f}')")
printf 'backend line coverage: %s%% (%s/%s lines), floor %s%%\n' "$pct" "$covered" "$total" "$FLOOR"

python3 - "$pct" "$FLOOR" <<'PY'
import sys
pct, floor = float(sys.argv[1]), float(sys.argv[2])
if pct < floor:
    print(f'FAIL: coverage {pct:.2f}% is below the floor of {floor:.2f}%', file=sys.stderr)
    raise SystemExit(1)
PY
