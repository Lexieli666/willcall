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

The run of record is the last one, named in the table. Earlier runs are kept because the tuning
history below refers to them, but a figure quoted anywhere in this repository comes from the run
of record — the table is generated from that file by `scripts/build-results-summary.sh`, so the
two cannot disagree.

<!-- FANOUT:BEGIN -->
Run of record: `load/results/2026-09-20/sse-5000-205019/sse-result.json`.

| Quantity | Measured | Target | Verdict |
|---|---|---|---|
| Concurrent SSE connections | **5,000** (1666 / 1667 / 1667 per replica, 0 failures) | ≥ 5,000 | met |
| Propagation, commit → client, p50 | **113 ms** | — | — |
| Propagation, commit → client, p99 | **223 ms** | 80–250 ms | met |
| Server-side flush, p99 | **2.1 ms** | — | — |
| Sequence gaps detected by clients | **0** over 15,200,000 delivered changes | — | — |
| Retained heap per connection | **75.7 KiB** | 10–60 KB | **missed** |
<!-- FANOUT:END -->

### Why propagation is where it is

The measurement starts at the PostgreSQL commit, not at the fan-out, and therefore contains three
waits that are all time a buyer experiences:

| Stage | Contribution |
|---|---|
| Outbox relay tick | 0–25 ms, mean ~12 ms |
| Coalescing window | 0–50 ms, mean ~25 ms |
| Serialise, queue, socket write, client parse | the rest |

The relay tick was 100 ms in the first run (`sse-5000-203547`, p99 305 ms); reducing it to 25 ms
and shrinking Tomcat's per-connection buffers took it through `sse-5000-204654` (252 ms) to the
run of record. Reducing the coalescing window would buy roughly 25 ms more at the cost of
multiplying the frame count — the wrong trade at 5,000 connections, where frame count is what the
CPU is spent on.

**The generator is part of the tail and is not separated out.** A single-threaded Node process
parsing 166,000 frames per second is plausibly a meaningful share of the last 50 ms, and it shares
a host with the service. The server's own flush timer is captured in each result file so a reader
can see how much of the latency the server accounts for, but the two have not been separated by
running the generator on a different machine. That is stated rather than assumed away, and it is
the first thing to do if this number ever needs to be defended.

### Why memory per connection is where it is

The retained figure in the table above is against a target of 10–60 KB. Two rounds of work moved
it, and neither of them was a change to how a connection is held:

1. **The first measurement was wrong**, not the software. Sampling resident set before and during
   the run attributed everything that grew — including the garbage from delivering fifteen million
   messages — to the connections, and reported 212 KiB. Forcing a collection and comparing the
   retained heap with connections open against the heap after closing them gave 90.5 KiB for the
   same code.
2. **Tomcat's per-connection application buffers** default to 8 KiB each way, sized for large
   request bodies. Every request on a stream is a few hundred bytes. Reducing them to 2 KiB took
   the figure to roughly 74–76 KiB, which is where it has stayed across the runs since: the
   remaining variation between runs is larger than any further tuning has produced, and is
   reported rather than averaged away.

What remains is Tomcat's per-connection processor state, the async request context Spring holds for
the duration of the stream, the outbound queue, and a parked virtual thread per connection. Getting
below 60 KB would mean not holding a thread per connection — a non-blocking writer pool over a
shared selector — which is a redesign rather than a tuning change, and is recorded as future work
rather than attempted at the end of a phase.

## Measured: the reservation path

From `load/results/2026-09-20/phase1-correctness/` and the `holds` and `flash` scenarios.

<!-- RESERVATION:BEGIN -->
Run of record: `load/results/2026-09-21/phase6-correctness/test-results.json`.

| Quantity | Measured | Target | Verdict |
|---|---|---|---|
| 10,000 concurrent holds at 500 seats | exactly 500 granted, 9,500 clean 409s, **0 oversells** across 50 runs | pass/fail | met |
| Wall clock for 10,000 concurrent attempts | 458 ms min, 494 ms median, 930 ms max | — | — |
| Flash sale, 10,000 buyers in 10 s for 5,000 seats | 50/50 runs with invariants intact, **0 oversells** | 0 oversells over 50 runs | met |
| Hold p99 at a controlled ~1,000 requests/s | **4,564 ms** | 60–150 ms | **missed** |
<!-- RESERVATION:END -->

Figures for sustained hold latency, flash-sale time-to-sell-out and the FIFO inversion rate are in
`load/RESULTS_SUMMARY.md`, each against its target.

## Measured: the hold ceiling

The plan assumed about 1,000 hold requests a second. The first attempt at that rate shed 65.7% of
its traffic and returned a p99 of 4.6 seconds, which answers "does it hold up at 1,000" — no — and
says nothing about where the ceiling is. The sweep walks the rate up instead.

<!-- SWEEP:BEGIN -->
Run of record: `load/results/2026-09-20/capacity-sweep-223133/capacity-sweep.json`, 45 s per step, three replicas.

| Offered req/s | Achieved req/s | Holds granted/s | Shed | Hold p50 | Hold p99 |
|---|---|---|---|---|---|
| 100 | 96 | **100** | 0.0% | 16 ms | 22 ms |
| 200 | 191 | **200** | 0.0% | 16 ms | 24 ms |
| 300 | 271 | **191** | 35.2% | 2,001 ms | 4,884 ms |
| 400 | 355 | **228** | 41.6% | 2,001 ms | 5,101 ms |
| 600 | 526 | **242** | 58.2% | 2,001 ms | 5,175 ms |
| 800 | 691 | **168** | 78.1% | 2,001 ms | 6,076 ms |
| 1,000 | 852 | **162** | 82.9% | 2,001 ms | 6,072 ms |

**Sustainable: 200 requests/s (67 per replica)** — the highest step that shed under 1% and kept p99 at or below 150 ms. Against a plan that assumed 1,000, that is a miss by a factor of 5.
<!-- SWEEP:END -->

Two things in that table matter more than the headline.

**p50 is exactly 2,001 ms from 300 requests/s onward.** That is HikariCP's `connection-timeout`,
visible as a measurement: past the knee, the median request spends its whole life waiting for a
connection that never comes and is then shed. It is not a latency distribution at that point, it is
a timeout.

**Goodput falls as offered load rises.** Holds actually granted peak at 242 a second with 600
offered, then drop to 168 at 800 and 162 at 1,000. Offering 67% more load gets 33% less work done.
This is worth stating plainly because the instinct when a service is shedding is to push harder,
and the measurement says that makes it worse — which is the whole argument for the waiting room
being a queue in front of the service rather than a retry loop inside the client.

## The bottleneck

**PostgreSQL row-lock contention on the seat table, reached through a 40-connection pool per
replica.**

The evidence is in three places rather than one number:

- **The shape of the concurrency result.** 10,000 simultaneous acquisitions resolve in about 600 ms
  on a 500-seat event — roughly 17,000 attempted acquisitions per second arriving at a database
  that grants 500 of them. `SKIP LOCKED` keeps the 9,500 failures cheap; without it they would
  queue behind the row locks instead of stepping over them. The successful ones still serialise on
  the rows they touch.
- **The knee in the sweep.** Nothing is shed at 200 requests/s and a third is shed at 300, with the
  median pinned to the pool timeout from there on. Whatever the constraint is, requests reach it by
  failing to get a connection.
- **The game day.** Blocking the seat table filled the pool in two seconds at 150 requests/s and
  `hikaricp_connections_pending` peaked at 304 across three replicas. The pool is reachable, which
  is the property it was sized for.

Three consequences follow, and they are why the system is built the way it is:

- **Adding replicas does not add reservation throughput.** It adds fan-out capacity and connection
  capacity. The queue's admission rate is therefore a property of the database, not of the replica
  count, which is why it is stored per event and measured rather than configured.
- **The connection pool is deliberately small** (40 per replica, 120 total against a 300-connection
  PostgreSQL). A larger pool would not make the database faster; it would let more requests wait
  inside it, converting a fast rejection into a slow one. Pool exhaustion is a game-day scenario
  precisely because the pool is sized to be reachable.
- **The waiting room exists to keep arrivals below this ceiling**, not to make the ceiling higher.
  200 requests/s is the number the admission rate should be set from on hardware like this.

### What the sweep does not settle

It says where the ceiling is, not what puts it there. A 120-connection pool serving 16 ms requests
should manage far more than 200 a second, so something holds a connection for much longer than a
hold takes. Candidates, none of them yet distinguished by measurement: the expiry sweeper, whose
own p99 sits at the 2 s lock timeout under load; the outbox relay running forty times a second on
every replica; or simple CPU contention with a load generator sharing the host. `make diagnose`
runs the rate that breaks while sampling pool occupancy, acquisition time, connection hold time,
sweeper duration and `pg_stat_activity`, which is the measurement that would separate them.

## Extrapolation to one million users## Extrapolation to one million users

> **Extrapolated, not measured.** Everything in this section is arithmetic on the figures above, and
> arithmetic is not evidence.

Taking the measured fan-out cost from the run of record and assuming it scales linearly:

<!-- EXTRAPOLATION:BEGIN -->
- **One million concurrent SSE connections is an extrapolated 600 replicas** of the
  same size (5,000 connections on 3 replicas, scaled linearly), or about
  78 GB of retained heap for connection state alone (extrapolated from
  75.7 KiB per connection).
<!-- EXTRAPOLATION:END -->
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
  failure shape, the reasoning about the pool is wrong. It was run on 2026-09-20. The application's
  half of the prediction held exactly — 4,427 requests shed as 503, not one 500 — and the edge
  turned that graceful shed into nine seconds of `502`, which nothing in this model had considered.
  [The postmortem](incidents/2026-09-20-connection-pool-exhaustion.md) is the correction.

## What here is not measured

One claim in this document has no measurement behind it, and it is the one that matters most for
sizing: **that added latency degrades capacity gradually, in proportion to connection-holding
time.** The scenario that would show it — 200 ms injected between a replica and PostgreSQL — could
not be run, because `tc` needs `NET_ADMIN` and the application containers do not have it. The
substitute was a ten-second database pause, which demonstrates the same mechanism arriving all at
once and says nothing about the slope. See
[the postmortem](incidents/2026-09-20-database-pause.md).

Until that run exists, "the bottleneck is connection-holding time rather than CPU" is supported by
the shape of the concurrency result and by the pool-exhaustion scenario, and not by a latency
sweep. The capacity sweep in `load/results/*/capacity-sweep-*/` measures where the ceiling is; it
does not measure what moves it.
