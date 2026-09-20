# 2026-09-20 — restarting Redis under load

- **Type:** game day
- **Severity:** none measurable. No failed request, no degraded response, no lost seat
- **Duration:** Redis unavailable for roughly one second
- **Author:** Lexie Li

## What happened

Redis was restarted while 150 hold requests a second flowed through the service. Nothing broke.
17,872 holds were granted, none failed, the invariant held, and the hold p99 was 77 ms against a
median of 8 ms — a visible bump, and the only trace the outage left in the numbers.

This is the scenario that confirms [ADR 0001](../adr/0001-postgresql-owns-correctness-redis-only-accelerates.md)
under real traffic rather than in an integration test. A postmortem for an incident where nothing
went wrong is still worth writing, because the claim it tests is the one the whole design rests on.

## Prediction made beforehand

Copied from `docs/game-day.md`, unedited:

> **Hypothesis.** Reservations are unaffected — this is the claim in ADR 0001 and it already has an
> integration test, so the game day is checking it under real traffic rather than discovering it.
> The waiting room degrades to open admission and `willcall_queue_degraded_total` rises. The rate
> limiter fails open. Cross-replica delta fan-out stops, so clients on replicas that did not make a
> change see a sequence gap and resync. Queue positions are lost: Redis has no persistence here, on
> purpose.
>
> **What would be a surprise.** A hold failing. A client that does not recover after the bus comes
> back. The admission bucket refilling to a burst and admitting a flood.

## Timeline

All times UTC.

| Time | Event |
|---|---|
| 21:59:32 | Load starts: 150 hold requests/s, three replicas |
| 22:00:12 | `docker compose restart redis` |
| 22:00:13 | Redis accepting connections again |
| 22:00:13 | 28 rate-limit checks fail open; 1 bus publish fails |
| 22:00:53 | Fault window closes |
| 22:01:32 | Load ends. 17,872 holds granted, 0 failures |
| 22:01:35 | `verify-invariants.sh`: 7/7 pass |

## Detection

`willcall_ratelimit_failed_open_total` rose by 28 and `willcall_bus_publish_failures_total` by 1.
Those two counters are the whole signal, and they exist because failing open is a decision that
has to be visible — a rate limiter that silently stops limiting is indistinguishable from one that
is working.

Nothing else moved. In particular no request failed, so no error-rate alert would have fired, which
is correct: the service was not degraded from a buyer's point of view.

## Root cause

Not applicable — no failure occurred. The mechanism that made it a non-event is the one the design
is arranged around: PostgreSQL owns every fact that must be true, and Redis holds only things the
service can rebuild or do without.

- **Holds and seat state** never touch Redis. A hold is a row lock and a partial unique index.
- **The rate limiter** fails open by construction, and says so in a counter.
- **The delta bus** is an accelerator: each replica delivers its own changes locally regardless of
  Redis, and clients that miss a cross-replica delta detect the sequence gap and resync. The one
  failed publish had no observable consequence at this connection count.
- **The sweeper** kept running: it is a `SKIP LOCKED` query, not a Redis lease.

## What made it worse, and what made it better

**Better:** the 77 ms p99 is the pause, and it is small because nothing waits on Redis
synchronously in the reservation path. The Lettuce timeout is 1 s, so the worst case for a request
that did touch Redis was bounded well below anything a buyer would notice.

**Neither:** the waiting room was not exercised. This event had no queue configured, so
`willcall_queue_degraded_total` never moved and the "degrades to open admission" half of the
hypothesis was not tested here. It is tested by an integration test, and the gap is stated rather
than counted as a pass.

## Impact

Measured from `load/results/2026-09-20/gameday-restart-redis-215932/`.

| Quantity | Measured |
|---|---|
| Requests during the run | 18,143 |
| Holds granted | 17,872 |
| Failures | 0 |
| `500`s / `503`s | 0 / 0 |
| Hold latency, median / p99 / max | 8 ms / 77 ms / 1,076 ms |
| Rate-limit checks that failed open | 28 |
| Bus publishes that failed | 1 |
| Holds expired by the sweeper during the run | 13,342 |
| Oversells | 0 |
| Invariant checks after the scenario | 7/7 pass |

## What was wrong in our understanding

Nothing, on the reservation path. One thing on the exercise itself: **the scenario as written does
not test the waiting room**, because the event the load script creates has no queue. The hypothesis
made four claims and the run could only speak to two of them. A game day that quietly tests half of
its own hypothesis is worth less than it appears.

## Actions

| # | Action | Kind | Owner | Status |
|---|---|---|---|---|
| 1 | Give the Redis scenario an event with a waiting room, so the admission-degradation half of the hypothesis is exercised | detect | Lexie Li | not done |
| 2 | Keep `willcall_ratelimit_failed_open_total` and `willcall_bus_publish_failures_total` on the dashboard; they were the only evidence the fault landed | detect | Lexie Li | done |

## Evidence

- Raw result directory: `load/results/2026-09-20/gameday-restart-redis-215932/`
- `metrics.txt` — `willcall_ratelimit_failed_open_total` +28,
  `willcall_bus_publish_failures_total` +1, `willcall_holds_granted_total` +17,872
- `timeline.txt` — restart at 22:00:12, back at 22:00:13
