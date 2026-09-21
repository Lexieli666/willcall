#!/usr/bin/env bash
# The 5,000-seat render budget, measured in a browser nothing is instrumenting.
#
# It runs from web/ because that is where Playwright is installed. See the comment at the top of
# the script for why this is not part of the end-to-end suite: the same render measures 19-43 ms
# here and 300-370 ms inside a Playwright test, so the suite was failing the budget on its own
# overhead.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT/web"
exec node perf/measure-seatmap-render.mjs "$@"
