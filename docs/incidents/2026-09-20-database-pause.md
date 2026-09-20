# 2026-09-20 — pausing PostgreSQL, and the fault we could not inject

- **Type:** game day
- **Severity:** 1,242 requests shed as `503` with `Retry-After` over 8 seconds; no failures, no
  lost seats
- **Duration:** 10 s of injected fault, 8 s of visible shedding, full recovery within 2 s
- **Author:** Lexie Li

## What happened

The scenario as designed adds 200 ms of network latency between a replica and PostgreSQL. It could
not be run: `tc` needs `NET_ADMIN`, which the application containers do not have and which is not
worth granting them to run an exercise. The runner fell back to freezing the PostgreSQL container
for ten seconds — **a different fault**, and the timeline says so at the moment it happens rather
than in a footnote afterwards.

The substitute is a harder fault than the original and answers a narrower question. Where 200 ms of
added latency would have shown the pool's capacity collapsing gradually, a pause shows it
collapsing immediately. The gradual version, which is the one the capacity model makes a claim
about, remains unmeasured.

## Prediction made beforehand

Copied from `docs/game-day.md`, unedited — written for the fault that did not run:

> **Hypothesis.** The most interesting of the four. Every database round trip costs 200 ms more,
> and a hold is several round trips, so hold latency rises to well over a second. Because
> connections are held for the duration of a transaction, the pool's effective capacity collapses —
> the same throughput now needs many times the connections — and the replica starts shedding 503s
> long before its CPU is busy. This is the clearest demonstration that the bottleneck named in
> `docs/capacity-model.md` is connection-holding time rather than CPU.

The mechanism it describes is what happened. The *shape* it describes — a gradual collapse as
latency eats the pool — is not what a pause produces, and this run is not evidence for it.

## Timeline

All times UTC.

| Time | Event |
|---|---|
| 22:02:03 | Load starts: 150 hold requests/s, three replicas |
| 22:02:44 | `tc` attempted; fails, no `NET_ADMIN`. Recorded in the timeline, and the fallback announced |
| 22:02:44 | `docker pause willcall-postgres-1` |
| 22:02:46 | `hikaricp_connections_pending` rises; peak 104 on `app3` |
| 22:02:48 | First `503`s — four seconds after the pause |
| 22:02:54 | `docker unpause willcall-postgres-1` |
| 22:02:56 | Last `503`. Shedding stops two seconds after the database returns |
| 22:03:34 | Fault window closes |
| 22:04:03 | Load ends. 16,647 holds granted |
| 22:04:06 | `verify-invariants.sh`: 7/7 pass |

## Detection

`hikaricp_connections_pending` moved first, two seconds before the first `503`. That ordering is
the useful part: the gauge is a leading indicator of shedding, not a description of it, which is
what makes `WillcallPoolSaturated` worth alerting on.

## Root cause

Not a defect. A frozen database means every open transaction stops mid-flight while holding its
pool connection; the pool empties within two seconds at 150 requests/s, and everything arriving
after that waits out the 2 s connection timeout and is shed. The 2,003 ms p99 is that timeout,
measured.

The part worth stating is what did **not** happen: no request became a `500`, no order was left
`PENDING` with a charged card, and the invariant held. A database that stops for ten seconds is one
of the few faults that can plausibly corrupt state, and it did not, because every state change is
one transaction that either commits or does not.

## What made it worse, and what made it better

**Worse:** nothing specific to this run. The `502`s from the pool-exhaustion scenario earlier the
same hour did not recur here, because the pause was short enough that no request reached nginx's
30 s read timeout — which is to say the edge survived by luck of duration, not by design. That is
fixed separately; see
[the pool-exhaustion postmortem](2026-09-20-connection-pool-exhaustion.md).

**Better:** recovery needed no intervention. Two seconds after `unpause`, shedding stopped; the
sweeper caught up on its own; no replica needed restarting.

## Impact

Measured from `load/results/2026-09-20/gameday-inject-latency-220203/`.

| Quantity | Measured |
|---|---|
| Requests during the run | 18,155 |
| Holds granted | 16,647 |
| Shed as `503` with `Retry-After` | 1,242, over 8 seconds |
| `500`s | 0 |
| Peak connections waiting for the pool | 104 on one replica |
| Hold latency, median / p99 / max | 10 ms / 2,003 ms / 10,411 ms |
| Time from database return to last `503` | 2 s |
| Oversells | 0 |
| Invariant checks after the scenario | 7/7 pass |

## What was wrong in our understanding

- **We assumed the scenario could be run.** It could not, and the interesting half of the
  hypothesis — that latency degrades capacity *gradually*, in proportion to connection-holding time
  — is still a claim in `docs/capacity-model.md` with no measurement behind it. The capacity model
  now says so where it makes the claim.
- **A fallback that "has a similar shape" is not the same measurement.** It is recorded as a
  different fault in the timeline, in the runner's own output, and here.

## Actions

| # | Action | Kind | Owner | Status |
|---|---|---|---|---|
| 1 | Record the substitution in the timeline at the moment it happens, not afterwards | detect | Lexie Li | done — the runner already does this |
| 2 | Run the real latency fault: either grant `NET_ADMIN` to a throwaway replica, or add the delay on the PostgreSQL side where the container can be given the capability | prevent | Lexie Li | not done |
| 3 | Mark the gradual-degradation claim in `docs/capacity-model.md` as unmeasured until action 2 runs | detect | Lexie Li | done |

## Evidence

- Raw result directory: `load/results/2026-09-20/gameday-inject-latency-220203/`
- `timeline.txt` — the `tc` failure and the announced substitution, in sequence
- `metrics.txt` — `hikaricp_connections_pending` peaking at 104, and the `503` series bounded by
  22:02:48 and 22:02:56
