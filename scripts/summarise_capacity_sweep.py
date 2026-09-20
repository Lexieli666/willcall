#!/usr/bin/env python3
"""Turn a capacity sweep's per-step summaries into capacity-sweep.json.

    scripts/summarise_capacity_sweep.py load/results/<date>/capacity-sweep-<time> [seconds-per-step]

Separate from the runner for the same reason as the other summarisers: the steps take six minutes
to produce and the arithmetic over them changes more often than they do.
"""
import glob
import json
import os
import sys


def main(out_dir, step_seconds):
    steps = []
    for step in sorted(glob.glob(os.path.join(out_dir, 'rate-*'))):
        path = os.path.join(step, 'summary.json')
        if not os.path.exists(path):
            continue
        metrics = json.load(open(path))['metrics']

        def metric(name, key):
            return metrics.get(name, {}).get(key)

        offered = int(os.path.basename(step).split('-')[1])
        granted = metric('willcall_holds_granted', 'count') or 0
        refused = metric('willcall_holds_refused', 'count') or 0
        total = metric('http_reqs', 'count') or 0
        # A 503 with Retry-After is the service shedding deliberately; it is neither a grant nor a
        # clean refusal, and calling it a fault would report the load shedder working as an outage.
        shed = max(total - granted - refused, 0)
        steps.append({
            'offeredRatePerSecond': offered,
            'achievedRatePerSecond': metric('http_reqs', 'rate'),
            # Requests answered per second is not the useful number; seats actually held is. They
            # diverge sharply once the service starts shedding, and the divergence is the finding.
            'grantedPerSecond': granted / step_seconds if step_seconds else None,
            'requests': total,
            'granted': granted,
            'refused409': refused,
            'shedOrFailed': shed,
            'shedFraction': round(shed / total, 4) if total else None,
            'droppedIterations': metric('dropped_iterations', 'count') or 0,
            'holdP50Ms': metric('willcall_hold_duration', 'med'),
            'holdP99Ms': metric('willcall_hold_duration', 'p(99)'),
            'holdMaxMs': metric('willcall_hold_duration', 'max'),
            'maxVus': metric('vus_max', 'value'),
        })

    # The sustainable rate: the highest step that shed under 1% and kept p99 inside the 150 ms
    # budget. Both conditions matter — a step can serve everything slowly, and that is not capacity.
    within = [s for s in steps
              if (s['shedFraction'] or 0) < 0.01 and (s['holdP99Ms'] or 1e9) <= 150]
    peak = max(steps, key=lambda s: s['grantedPerSecond'] or 0) if steps else None

    report = {
        'label': 'local Docker Compose, not AWS',
        'stepSeconds': step_seconds,
        'replicas': 3,
        'steps': steps,
        'sustainableRatePerSecond': max((s['offeredRatePerSecond'] for s in within), default=None),
        'criterion': 'highest offered rate that shed under 1% of requests '
                     'and kept hold p99 at or below 150 ms',
        'peakGoodput': {
            'atOfferedRate': peak['offeredRatePerSecond'],
            'grantedPerSecond': round(peak['grantedPerSecond'], 1),
        } if peak else None,
    }
    if report['sustainableRatePerSecond']:
        report['sustainableRatePerReplica'] = report['sustainableRatePerSecond'] / report['replicas']
    # Offering more load and getting less work done. Worth naming, because the instinct when a
    # service is shedding is to retry harder, and this is the measurement that says not to.
    if peak:
        beyond = [s for s in steps if s['offeredRatePerSecond'] > peak['offeredRatePerSecond']]
        if beyond:
            worst = min(beyond, key=lambda s: s['grantedPerSecond'] or 0)
            report['congestionCollapse'] = {
                'peakGrantedPerSecond': round(peak['grantedPerSecond'], 1),
                'atOfferedRate': peak['offeredRatePerSecond'],
                'fallsToGrantedPerSecond': round(worst['grantedPerSecond'], 1),
                'atHigherOfferedRate': worst['offeredRatePerSecond'],
            }
    json.dump(report, open(os.path.join(out_dir, 'capacity-sweep.json'), 'w'), indent=2)

    print('')
    print(f"{'offered':>8} {'achieved':>9} {'granted/s':>10} {'shed%':>6} {'p50 ms':>8} {'p99 ms':>9}")
    for s in steps:
        print(f"{s['offeredRatePerSecond']:>8} {s['achievedRatePerSecond']:>9.0f} "
              f"{s['grantedPerSecond']:>10.0f} {(s['shedFraction'] or 0) * 100:>5.1f}% "
              f"{s['holdP50Ms']:>8.0f} {s['holdP99Ms']:>9.0f}")
    print('')
    print(f"sustainable rate : {report['sustainableRatePerSecond']} requests/s "
          f"({report.get('sustainableRatePerReplica', 0):.0f} per replica)")
    print(f"criterion        : {report['criterion']}")
    collapse = report.get('congestionCollapse')
    if collapse:
        print(f"peak goodput     : {collapse['peakGrantedPerSecond']:.0f} holds/s at "
              f"{collapse['atOfferedRate']} offered, falling to "
              f"{collapse['fallsToGrantedPerSecond']:.0f} at {collapse['atHigherOfferedRate']}")


if __name__ == '__main__':
    if len(sys.argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        raise SystemExit(2)
    main(sys.argv[1], float(sys.argv[2]) if len(sys.argv) == 3 else 45.0)
