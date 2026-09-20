#!/usr/bin/env bash
# Every relative link in the committed markdown points at a file git is tracking.
#
# Two failures this catches, both of which have happened here. A document links to a file that was
# never committed, because the root .gitignore excludes /*.md with an allow-list and `git add -A`
# skips the rest without saying so. And a document links to a file that was renamed, which nothing
# notices until a reader clicks it — on a public repository, that reader is the audience.
#
# Anchors and external links are out of scope: an anchor needs a markdown parser to check properly,
# and an external link fails for reasons that are not this repository's fault.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - <<'PY'
import os
import re
import subprocess
import sys

tracked = set(subprocess.run(['git', 'ls-files'], capture_output=True, text=True,
                             check=True).stdout.split('\n'))
markdown = [f for f in tracked if f.endswith('.md')]

link = re.compile(r'\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')
broken = []
untracked = []
placeholders = 0

for doc in markdown:
    directory = os.path.dirname(doc)
    with open(doc, errors='replace') as handle:
        for number, line in enumerate(handle, 1):
            for target in link.findall(line):
                if target.startswith(('http://', 'https://', 'mailto:', '#')):
                    continue
                # A link to a heading in another file: check the file, ignore the anchor.
                path = target.split('#', 1)[0]
                if not path:
                    continue
                # A template's placeholder is not a broken link. Matched on the placeholder
                # markers rather than on the filename, so exempting a template does not exempt
                # everything else in it.
                if any(marker in path for marker in ('NNNN', '<', '....')):
                    placeholders += 1
                    continue
                resolved = os.path.normpath(os.path.join(directory, path))
                if os.path.isdir(resolved):
                    continue
                if not os.path.exists(resolved):
                    broken.append(f'{doc}:{number}  ->  {target}')
                elif resolved not in tracked:
                    # It exists on this machine and would 404 for everybody else.
                    untracked.append(f'{doc}:{number}  ->  {target}')

for label, problems in (('missing', broken), ('present locally but not committed', untracked)):
    if problems:
        print(f'{len(problems)} link(s) {label}:')
        for problem in problems:
            print(f'  {problem}')

total = len(broken) + len(untracked)
print(f'{len(markdown)} markdown files checked, {total} broken link(s), '
      f'{placeholders} template placeholder(s) skipped')
sys.exit(1 if total else 0)
PY
