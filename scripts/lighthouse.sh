#!/usr/bin/env bash
# Run the Lighthouse budgets.
#
# LOCALAPPDATA and TMP are overridden because under WSL they are inherited from the Windows
# environment as `C:\Users\...`. Chrome then creates a profile directory whose *name* is that
# literal string, inside whatever directory it was launched from — three of them landed in
# web/ and were nearly committed before the hygiene check caught them.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT/web"

WORK_DIR="$(mktemp -d -t willcall-lighthouse-XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

export LOCALAPPDATA="$WORK_DIR"
export APPDATA="$WORK_DIR"
export TMP="$WORK_DIR"
export TEMP="$WORK_DIR"
export TMPDIR="$WORK_DIR"
export WILLCALL_LHCI_URL="${WILLCALL_LHCI_URL:-http://127.0.0.1:4173/}"

if [ -z "${CHROME_PATH:-}" ] && [ -d "$HOME/.cache/ms-playwright" ]; then
  CHROME_PATH="$(node -e "try{console.log(require('playwright').chromium.executablePath())}catch(e){}" 2>/dev/null || true)"
  [ -n "$CHROME_PATH" ] && export CHROME_PATH
fi

npx lhci autorun --config=lighthouserc.cjs "$@"
