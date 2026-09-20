#!/usr/bin/env python3
"""Rebuild gameday.json from a scenario's raw files.

Separate from run-game-day.sh so a parsing bug can be fixed and the affected runs re-summarised
without re-running the game day: metrics.txt, timeline.txt and summary.json are the measurement,
and the JSON is a view over them.

    scripts/summarise_game_day.py load/results/<date>/gameday-<scenario>-<time>
"""
import collections
import json
import os
import re
import sys


def sampled_totals(path):
    """Sum each counter's positive steps, treating a step backwards as a process restart.

    Last-minus-first is wrong the moment a replica restarts: its counters return to zero and the
    difference goes negative. The kill-replica run reported -93,580 responses with status 201 this
    way, which is not merely inaccurate but impossible. This is what Prometheus' own increase()
    does, and it counts the resets, because "the replica restarted" is itself the evidence that
    the fault landed.
    """
    previous = {}
    totals = collections.Counter()
    resets = collections.Counter()
    if not os.path.exists(path):
        return totals, resets
    for line in open(path, errors='replace'):
        parts = line.split(' ', 2)
        if len(parts) < 3:
            continue
        _, replica, metric = parts
        metric = metric.strip()
        if not metric or metric.startswith('#'):
            continue
        name, _, value = metric.rpartition(' ')
        # Counters only. hikaricp_connections_pending is a gauge and falls back to zero every time
        # the pool drains, so treating every decrease as a restart reported eighty restarts on a
        # replica nobody touched - which would have made the one real restart invisible among them.
        bare = name.split('{', 1)[0]
        if not (bare.endswith('_total') or bare.endswith('_count')):
            continue
        try:
            value = float(value)
        except ValueError:
            continue
        key = (replica, name)
        if key not in previous:
            # The first sample is the baseline: work done before the run is not this run's.
            previous[key] = value
            continue
        if value >= previous[key]:
            totals[key] += value - previous[key]
        else:
            totals[key] += value
            resets[replica] += 1
        previous[key] = value
    return totals, resets


def peak_gauge(path, needle):
    """The highest value any replica reported for a gauge, and when."""
    best = None
    if not os.path.exists(path):
        return None
    for line in open(path, errors='replace'):
        parts = line.split(' ', 2)
        if len(parts) < 3 or needle not in parts[2]:
            continue
        name, _, value = parts[2].strip().rpartition(' ')
        try:
            value = float(value)
        except ValueError:
            continue
        if best is None or value > best[0]:
            best = (value, parts[1], int(parts[0]))
    if best is None:
        return None
    return {'value': best[0], 'replica': best[1], 'atEpochSeconds': best[2]}


def main(out_dir):
    totals, resets = sampled_totals(os.path.join(out_dir, 'metrics.txt'))

    by_status = collections.Counter()
    for (_, name), value in totals.items():
        if not name.startswith('http_server_requests_seconds_count'):
            continue
        status = re.search(r'status="(\d+)"', name)
        if status:
            by_status[status.group(1)] += value

    summary = {}
    summary_path = os.path.join(out_dir, 'summary.json')
    if os.path.exists(summary_path):
        metrics = json.load(open(summary_path)).get('metrics', {})
        summary = {
            'holdP99Ms': metrics.get('willcall_hold_duration', {}).get('p(99)'),
            'holdMaxMs': metrics.get('willcall_hold_duration', {}).get('max'),
            'holdMedianMs': metrics.get('willcall_hold_duration', {}).get('med'),
            'holdsGranted': metrics.get('willcall_holds_granted', {}).get('count'),
            'holdsRefused': metrics.get('willcall_holds_refused', {}).get('count'),
            'errorRate': metrics.get('willcall_hold_errors', {}).get('value'),
            'requests': metrics.get('http_reqs', {}).get('count'),
            'droppedIterations': metrics.get('dropped_iterations', {}).get('count', 0),
        }

    existing = {}
    report_path = os.path.join(out_dir, 'gameday.json')
    if os.path.exists(report_path):
        existing = json.load(open(report_path))

    scenario = existing.get('scenario') or os.path.basename(out_dir).split('-')[1]
    report = {
        'scenario': scenario,
        'faultInjectedAt': existing.get('faultInjectedAt'),
        'k6ExitCode': existing.get('k6ExitCode'),
        'invariantsHeld': existing.get('invariantsHeld'),
        'responsesByStatus': {k: round(v) for k, v in sorted(by_status.items())},
        # Series that went backwards, per replica. A restart resets every series at once, so this
        # counts series and not restarts; what it answers is "did this replica's process go away",
        # and zero here during kill-replica would mean the fault never landed.
        'counterSeriesResetByReplica': dict(sorted(resets.items())),
        'peakPoolPending': peak_gauge(os.path.join(out_dir, 'metrics.txt'),
                                      'hikaricp_connections_pending'),
        'load': summary,
    }
    json.dump(report, open(report_path, 'w'), indent=2)

    print(f"scenario            : {report['scenario']}")
    print(f"responses by status : {report['responsesByStatus']}")
    print(f"series reset        : {report['counterSeriesResetByReplica'] or 'none'}")
    peak = report['peakPoolPending']
    if peak:
        print(f"peak pool waiters   : {peak['value']:.0f} on {peak['replica']}")
    print(f"invariants held     : {report['invariantsHeld']}")
    if summary.get('holdP99Ms') is not None:
        print(f"hold med/p99/max ms : {summary['holdMedianMs']:.0f} / "
              f"{summary['holdP99Ms']:.0f} / {summary['holdMaxMs']:.0f}")


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        raise SystemExit(2)
    main(sys.argv[1])
