#!/usr/bin/env python3
"""Summarise a flash-sale suite directory from its raw per-run files.

Kept separate from the runner so a finished suite can be re-summarised without re-running it —
which matters, because adding a figure to the summary should never mean spending another forty
minutes of measurement to get it.

Usage: aggregate_flash_suite.py <suite-directory>
"""
import glob
import json
import os
import re
import statistics
import subprocess
import sys


def main(suite_dir: str) -> None:
    per_run = []

    # run-[0-9][0-9][0-9] rather than run-*: the suite writes run-context.md beside the run
    # directories, and run-* matched it as a fifty-first run that was 'still in progress'.
    for run_dir in sorted(glob.glob(f'{suite_dir}/run-[0-9][0-9][0-9]')):
        entry = {'run': os.path.basename(run_dir)}

        summary_path = os.path.join(run_dir, 'summary.json')
        if os.path.exists(summary_path):
            metrics = json.load(open(summary_path)).get('metrics', {})

            def count(name, metrics=metrics):
                return metrics.get(name, {}).get('count', 0)

            def trend(name, stat, metrics=metrics):
                return metrics.get(name, {}).get(stat)

            entry['granted'] = count('willcall_flash_granted')
            entry['refused'] = count('willcall_flash_refused')
            entry['confirmed'] = count('willcall_flash_confirmed')
            entry['shed'] = count('willcall_flash_shed')
            entry['serverErrors'] = count('willcall_flash_server_errors')
            entry['holdP99Ms'] = trend('willcall_flash_hold_duration', 'p(99)')
            entry['confirmP99Ms'] = trend('willcall_flash_confirm_duration', 'p(99)')
            entry['soldOutSeconds'] = trend('willcall_flash_sold_out_seconds', 'avg')

        stdout_path = os.path.join(run_dir, 'stdout.log')
        if os.path.exists(stdout_path):
            text = open(stdout_path, errors='replace').read()
            tally = re.search(r'(\d+) sold, (\d+) held, (\d+) available', text)
            if tally:
                entry['sold'] = int(tally.group(1))
                entry['held'] = int(tally.group(2))
                entry['available'] = int(tally.group(3))
            if entry.get('soldOutSeconds') is None:
                spoken = re.search(r'sold out after ([0-9.]+) s', text)
                if spoken:
                    entry['soldOutSeconds'] = float(spoken.group(1))

        # Three outcomes, not two. A run still in progress has an empty invariant log, and
        # treating that as a violation would report an oversell that never happened — which it
        # did, the first time this was run against a suite that had not finished.
        invariant_path = os.path.join(run_dir, 'verify-invariants.log')
        log = open(invariant_path, errors='replace').read() if os.path.exists(invariant_path) else ''
        if 'all invariant checks passed' in log:
            entry['outcome'] = 'passed'
        elif 'INVARIANT VIOLATED' in log or 'invariant check(s) failed' in log:
            entry['outcome'] = 'violated'
            entry['violation'] = '\n'.join(
                line for line in log.splitlines() if 'VIOLATED' in line or '|' in line
            )[:2000]
        else:
            entry['outcome'] = 'incomplete'
        entry['invariantsHeld'] = entry['outcome'] == 'passed'
        per_run.append(entry)

    def collect(key):
        return [r[key] for r in per_run if isinstance(r.get(key), (int, float))]

    def spread(values):
        if not values:
            return {'min': None, 'median': None, 'max': None}
        return {
            'min': min(values),
            'median': statistics.median(values),
            'max': max(values),
        }

    complete = [r for r in per_run if r['outcome'] != 'incomplete']
    incomplete = [r for r in per_run if r['outcome'] == 'incomplete']

    report = {
        'runs': len(complete),
        'runsIncomplete': len(incomplete),
        'commit': subprocess.run(
            ['git', 'rev-parse', 'HEAD'], capture_output=True, text=True
        ).stdout.strip(),
        'runsWithInvariantsHeld': sum(1 for r in complete if r['outcome'] == 'passed'),
        'runsWithServerErrors': sum(1 for r in complete if r.get('serverErrors', 0) > 0),
        'totalServerErrors': sum(r.get('serverErrors', 0) for r in complete),
        # An oversell is defined by the database, not by the run's own output: a run whose
        # invariant check failed is counted as one even if k6 reported everything fine. Runs that
        # have not finished are excluded rather than assumed either way.
        'oversells': sum(1 for r in complete if r['outcome'] == 'violated'),
        'violations': [
            {'run': r['run'], 'detail': r.get('violation')}
            for r in complete
            if r['outcome'] == 'violated'
        ],
        'holdP99Ms': spread(collect('holdP99Ms')),
        'confirmP99Ms': spread(collect('confirmP99Ms')),
        'soldOutSeconds': spread(collect('soldOutSeconds')),
        'seatsSold': spread(collect('sold')),
        'shedRequests': spread(collect('shed')),
        'perRun': per_run,
    }

    out = os.path.join(suite_dir, 'flash-suite.json')
    json.dump(report, open(out, 'w'), indent=2)

    print(f"runs completed              : {report['runs']}"
          + (f" ({report['runsIncomplete']} still in progress)" if report['runsIncomplete'] else ''))
    print(f"invariants held             : {report['runsWithInvariantsHeld']}/{report['runs']}")
    print(f"oversells                   : {report['oversells']}")
    print(f"runs with a 5xx             : {report['runsWithServerErrors']} "
          f"({report['totalServerErrors']} total)")
    for label, key, suffix in (
        ('sold out after (s)', 'soldOutSeconds', ''),
        ('hold p99 (ms)', 'holdP99Ms', ''),
        ('seats sold', 'seatsSold', ''),
        ('requests shed (503)', 'shedRequests', ''),
    ):
        values = report[key]
        if values['median'] is None:
            continue
        print(f"{label:28}: min {values['min']:.0f}, median {values['median']:.0f}, "
              f"max {values['max']:.0f}{suffix}")
    for violation in report['violations']:
        print(f"\nVIOLATION in {violation['run']}:\n{violation['detail']}")

    print(f'\nwritten to {out}')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
