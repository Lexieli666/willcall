#!/usr/bin/env python3
"""Turn a captured plan file into seed.json.

Separate from seed-large-dataset.sh for the same reason the game-day summariser is separate from
its runner: re-collecting plans takes seconds and re-seeding a million rows does not, so a change
to how plans are read must not require the data to be built again.

    scripts/summarise_query_plans.py load/results/<date>/query-plans
"""
import json
import os
import re
import sys


def main(out_dir):
    text = open(os.path.join(out_dir, 'plans.txt'), errors='replace').read()

    # Sections start at a numbered heading; a heading may be 1..8 or a variant like 3b.
    heading = re.compile(r'^(\d+[a-z]?)\. (.+?)\s+\[(hot path|analytical)\]\s*$', re.MULTILINE)
    marks = list(heading.finditer(text))
    plans = []
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        body = text[m.end():end]
        # A section may hold more than one plan (3b compares two forms of the same query); the
        # slowest is the one that characterises it.
        times = [float(t) for t in re.findall(r'Execution Time: ([0-9.]+) ms', body)]
        plans.append({
            'number': m.group(1),
            'name': m.group(2).strip(),
            'classification': m.group(3),
            'hotPath': m.group(3) == 'hot path',
            'executionMs': max(times) if times else None,
            'executionMsAll': times,
            'seqScan': bool(re.search(r'Seq Scan on', body)),
            'indexScan': bool(re.search(r'Index (Only )?Scan', body)),
        })

    counts = {}
    sizes_path = os.path.join(out_dir, 'table-sizes.txt')
    if os.path.exists(sizes_path):
        for line in open(sizes_path, errors='replace'):
            parts = [p.strip() for p in line.split('|')]
            if len(parts) >= 2 and re.fullmatch(r'[0-9,]+', parts[1] or ''):
                counts[parts[0]] = int(parts[1].replace(',', ''))

    event = re.search(r"Plans taken against event '([0-9a-f-]+)' with (\d+) seats available", text)
    report = {
        'label': 'local Docker Compose, not AWS',
        'eventUnderTest': event.group(1) if event else None,
        'availableSeatsInThatEvent': int(event.group(2)) if event else None,
        'rowCounts': {
            'users': counts.get('seed_users', 0),
            'events': counts.get('events', 0),
            'orders': counts.get('seed_orders', 0),
        },
        'tableRowCounts': counts,
        'plans': plans,
    }
    json.dump(report, open(os.path.join(out_dir, 'seed.json'), 'w'), indent=2)

    hot_seq = [p['name'] for p in plans if p['hotPath'] and p['seqScan']]
    print(f"plans captured        : {len(plans)}")
    print('rows                  : ' + ', '.join(f'{k} {v:,}' for k, v in report['rowCounts'].items()))
    print(f"hot paths seq-scanning: {', '.join(hot_seq) if hot_seq else 'none'}")


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        raise SystemExit(2)
    main(sys.argv[1])
