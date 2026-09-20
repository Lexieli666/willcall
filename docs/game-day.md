# Game day

A game day is a rehearsal for a failure you expect, run while somebody is watching, so that the
first time you see it is not at three in the morning. The value is in what it reveals, not in
whether the system survives — a game day where everything went fine and nothing was learned was a
waste of an afternoon.

Each scenario below states the hypothesis before it is run. Writing the prediction down first is
what makes the exercise falsifiable: an outcome that matches a prediction made afterwards is not
evidence of understanding.

## Rules

1. **Predict first.** What will break, what will the dashboard show, what will a buyer see.
2. **Run against a real stack** with traffic flowing, not an idle one. A failure with no load is a
   different failure.
3. **Watch the dashboard, not the logs.** If a scenario is only diagnosable from the logs, that is
   a finding about the dashboard.
4. **Write the postmortem whether or not anything broke**, including the predictions that were
   wrong.
5. **Run `verify-invariants.sh` afterwards, every time.** Correctness is the thing that must
   survive all of this.

## Scenarios

### 1. Kill a replica

```bash
docker compose kill app2
# ... observe ...
docker compose up -d app2
```

**Hypothesis.** The proxy removes it within `fail_timeout` (5 s) and in-flight requests to it fail.
SSE connections on that replica die and reconnect to the survivors, each costing a snapshot, so
open-connection counts on app1 and app3 jump by roughly half of app2's share each. No oversells,
because a killed replica cannot half-commit a transaction. The restarted replica comes back with
zero connections and stays near zero, because nothing rebalances long-lived streams.

**What would be a surprise.** Holds failing on the surviving replicas; the sweeper stopping; the
restarted replica taking traffic but not connections and nobody noticing.

### 2. Exhaust the PostgreSQL connection pool

```bash
# Hold every connection open from outside the application.
for i in $(seq 1 130); do
  docker exec -d willcall-postgres-1 psql -U willcall -d willcall -c 'select pg_sleep(120)'
done
```

**Hypothesis.** The pool saturates, `hikaricp_connections_pending` rises, and requests answer 503
with `Retry-After` rather than 500 — that mapping was added after the first flash-sale suite
reported nearly two thousand 500s. The `WillcallPoolSaturated` alert fires within five minutes.
Buyers see "the service is at capacity", not an error page. No oversells: a transaction that never
started cannot corrupt anything.

**What would be a surprise.** Any 500s. A stalled sweeper that never recovers after the pool frees
up. Holds succeeding while checkouts fail in a way that leaves orders PENDING forever.

### 3. Restart Redis

```bash
docker compose restart redis
```

**Hypothesis.** Reservations are unaffected — this is the claim in
[ADR 0001](adr/0001-postgresql-owns-correctness-redis-only-accelerates.md) and it already has an
integration test, so the game day is checking it under real traffic rather than discovering it. The
waiting room degrades to open admission and `willcall_queue_degraded_total` rises. The rate limiter
fails open. Cross-replica delta fan-out stops, so clients on replicas that did not make a change
see a sequence gap and resync. Queue positions are lost: Redis has no persistence here, on purpose.

**What would be a surprise.** A hold failing. A client that does not recover after the bus comes
back. The admission bucket refilling to a burst and admitting a flood.

### 4. Inject 200 ms of latency

```bash
# Between the application and PostgreSQL.
docker exec willcall-app1-1 tc qdisc add dev eth0 root netem delay 200ms
```

**Hypothesis.** The most interesting of the four. Every database round trip costs 200 ms more, and
a hold is several round trips, so hold latency rises to well over a second. Because connections are
held for the duration of a transaction, the pool's effective capacity collapses — the same
throughput now needs many times the connections — and the replica starts shedding 503s long before
its CPU is busy. This is the clearest demonstration that the bottleneck named in
`docs/capacity-model.md` is connection-holding time rather than CPU.

**What would be a surprise.** The other two replicas slowing down as well, which would mean they
share something they should not.

## Afterwards

```bash
./scripts/verify-invariants.sh
```

Then write `docs/incidents/<date>-<name>.md` from `docs/incidents/TEMPLATE.md`, one per scenario,
including the predictions that were wrong.

## Status

> **Run against the local Docker Compose stack.** Scenarios 1 to 4 are reproducible locally and
> their findings are real findings about the software. What local Compose cannot exercise is
> Application Load Balancer behaviour, cross-availability-zone failure, and RDS failover — so a
> game day against a deployed AWS stack remains outstanding, and is listed in `PROGRESS.md` rather
> than being implied by the postmortems here.
