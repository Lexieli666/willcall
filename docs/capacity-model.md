# Capacity model

> **Status:** the per-replica figures below are measured. The one-million-user figure is an
> **extrapolation** and is labelled as one in the same sentence every time it appears.

Every number here comes from a file under `load/results/`. Where a target from the project plan was
missed, the measured value is given next to the target and the gap is stated — see
`load/RESULTS_SUMMARY.md` for the full mapping.

## What was measured, and on what

**Local Docker Compose, not AWS.** See
[ADR 0004](adr/0004-run-on-local-docker-compose-until-aws-credentials-exist.md).

| Component | Sizing |
|---|---|
| Application | 3 replicas, 2 vCPU and 2 GiB each (enforced by `deploy.resources.limits`) |
| PostgreSQL | 16-alpine, 4 vCPU, 8 GiB |
| Redis | 7-alpine, 2 vCPU, 2 GiB |
| Edge | nginx 1.27, `least_conn`, buffering off for the stream path |
| Host | 32 logical cores, 31 GiB, Linux 6.6 under WSL2 |
| Load generators | On the same host as the service, competing for CPU |

The last line matters and is repeated in every results file: the generator and the service share a
machine. Percentiles include no network latency and do include generator CPU contention.

## The shape of the system

```
buyer ──HTTP──▶ nginx ──▶ one of three replicas ──▶ PostgreSQL (row locks)
                                │
                                ├──▶ Redis (queue, rate limits, pub/sub)
                                └──▶ outbox relay ──▶ Redis pub/sub ──▶ every replica's SSE hub
```

Two paths with different constraints:

- **The reservation path** is bound by PostgreSQL row locks and connection-pool availability. It
  scales with the database, not with the number of replicas.
- **The fan-out path** is bound by sockets and CPU on the replicas. It scales with replicas.

They fail differently, which is why they have separate load scenarios and separate budgets.

## Measured: the fan-out path

From `load/results/2026-09-20/sse-5000-*/`.

| Quantity | Measured | Target | Verdict |
|---|---|---|---|
| Concurrent SSE connections | **5,000** (1,667 per replica, 0 failures) | ≥ 5,000 | met |
| Propagation, commit → client, p50 | **129 ms** | — | — |
| Propagation, commit → client, p99 | **252 ms** | 80–250 ms | **missed by 2 ms** |
| Sequence gaps detected by clients | **0** over 15,000,000 delivered changes | — | — |
| Retained heap per connection | **73.9 KiB** | 10–60 KB | **missed** |

### Why propagation is where it is

The measurement starts at the PostgreSQL commit, not at the fan-out, and therefore contains three
waits that are all time a buyer experiences:

| Stage | Contribution |
|---|---|
| Outbox relay tick | 0–25 ms, mean ~12 ms |
| Coalescing window | 0–50 ms, mean ~25 ms |
| Serialise, queue, socket write, client parse | the rest |

The relay tick was 100 ms in the first run and the p99 was 305 ms; reducing it to 25 ms took the
p99 to 252 ms. Reducing the coalescing window would buy roughly 25 ms more at the cost of
multiplying the frame count — the wrong trade at 5,000 connections, where frame count is what the
CPU is spent on.

**The generator is part of the tail and is not separated out.** A single-threaded Node process
parsing 166,000 frames per second is plausibly a meaningful share of the last 50 ms, and it shares
a host with the service. The server's own flush timer is captured in each result file so a reader
can see how much of the latency the server accounts for, but the two have not been separated by
running the generator on a different machine. That is stated rather than assumed away, and it is
the first thing to do if this number ever needs to be defended.

### Why memory per connection is where it is

73.9 KiB retained per connection, against a target of 10–60 KB. Two rounds of work moved it:

1. **The first measurement was wrong**, not the software. Sampling resident set before and during
   the run attributed everything that grew — including the garbage from delivering fifteen million
   messages — to the connections, and reported 212 KiB. Forcing a collection and comparing the
   retained heap with connections open against the heap after closing them gave 90.5 KiB for the
   same code.
2. **Tomcat's per-connection application buffers** default to 8 KiB each way, sized for large
   request bodies. Every request on a stream is a few hundred bytes. Reducing them to 2 KiB took
   the figure to 73.9 KiB.

What remains is Tomcat's per-connection processor state, the async request context Spring holds for
the duration of the stream, the outbound queue, and a parked virtual thread per connection. Getting
below 60 KB would mean not holding a thread per connection — a non-blocking writer pool over a
shared selector — which is a redesign rather than a tuning change, and is recorded as future work
rather than attempted at the end of a phase.

## Measured: the reservation path

From `load/results/2026-09-20/phase1-correctness/` and the `holds` and `flash` scenarios.

| Quantity | Measured | Target | Verdict |
|---|---|---|---|
| 10,000 concurrent holds at 500 seats | exactly 500 granted, 9,500 clean 409s, **0 oversells** across 50 runs | pass/fail | met |
| Wall clock for 10,000 concurrent attempts | 555 ms min, 593 ms median, 957 ms max | — | — |

Figures for sustained hold latency, flash-sale time-to-sell-out and the FIFO inversion rate are in
`load/RESULTS_SUMMARY.md`, each against its target.

## The bottleneck

**PostgreSQL row-lock contention on the seat table, reached through a 20-connection pool per
replica.**

The evidence is in the shape of the concurrency result rather than in a single number: 10,000
simultaneous acquisitions resolve in about 600 ms on a 500-seat event, which is roughly 17,000
attempted acquisitions per second arriving at a database that grants 500 of them. `SKIP LOCKED` is
what keeps the 9,500 failures cheap — without it they would queue behind the row locks instead of
stepping over them — but the successful ones still serialise on the rows they touch.

Three consequences follow, and they are why the system is built the way it is:

- **Adding replicas does not add reservation throughput.** It adds fan-out capacity and connection
  capacity. The queue's admission rate is therefore a property of the database, not of the replica
  count, which is why it is stored per event and measured rather than configured.
- **The connection pool is deliberately small** (20 per replica, 60 total against a 300-connection
  PostgreSQL). A larger pool would not make the database faster; it would let more requests wait
  inside it, converting a fast rejection into a slow one. Pool exhaustion is a game-day scenario
  precisely because the pool is sized to be reachable.
- **The waiting room exists to keep arrivals below this ceiling**, not to make the ceiling higher.

## Extrapolation to one million users

> **Extrapolated, not measured.** Everything in this section is arithmetic on the figures above, and
> arithmetic is not evidence.

Taking the measured fan-out cost — 5,000 connections on three 2-vCPU replicas at 73.9 KiB of
retained heap each, with propagation p99 at 252 ms — and assuming it scales linearly:

- **One million concurrent SSE connections is an extrapolated 600 replicas** of the same size
  (1,000,000 ÷ 5,000 × 3), or about 74 GB of retained heap for connection state alone
  (extrapolated).
- **The pub/sub fan-out does not scale that way.** Every replica receives every delta, so the
  per-replica delivery cost grows with the number of connections *on that replica* while the
  message-receive cost stays constant — but the Redis publish rate grows with the change rate, not
  the audience, so Redis is not the constraint. The constraint at that size would be the 600
  replicas' aggregate socket and CPU load, and the per-event coalescing that currently happens
  independently on each of them.
- **The reservation path would not scale at all**, because it is bound by one PostgreSQL instance.
  One million buyers for a fixed inventory is the same number of seats being fought over by two
  hundred times as many people: the *admission rate* would be unchanged and the *queue* would be
  two hundred times longer. The honest statement is that a million-user event needs the same
  database and a much longer wait, not two hundred times the hardware.

**What would make this a measurement rather than an extrapolation:** a run at 50,000 connections
across 30 replicas, on separate load-generator hosts, with the propagation percentile and the
retained-heap figure re-measured at that size. Two orders of magnitude of extrapolation from 5,000
is more than the numbers can carry, and the linear assumption is exactly what a run at 50,000 would
test.

## What would falsify this model

- The SSE scenario re-run at a higher connection count: if propagation degrades faster than
  linearly, the fan-out section is wrong.
- The holds scenario at a higher arrival rate: if throughput keeps rising after the pool saturates,
  the named bottleneck is wrong.
- The game day's pool-exhaustion scenario: if exhausting the pool does not produce the predicted
  failure shape, the reasoning about the pool is wrong.
