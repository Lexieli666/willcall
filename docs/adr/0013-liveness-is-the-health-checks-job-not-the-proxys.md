# ADR 0013: Liveness is the health check's job, not the proxy's

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

nginx does passive health checking: count failures per upstream, and after `max_fails` within
`fail_timeout`, stop sending it traffic. The configuration was `max_fails=3 fail_timeout=5s`, which
is the conventional choice and reads as obviously sensible.

The game day on 2026-09-20 held an `ACCESS EXCLUSIVE` lock on the seat table for forty seconds
while 150 hold requests a second flowed through the edge. The application did what it was designed
to do: 4,427 requests shed as `503` with `Retry-After`, not one `500`. Thirty seconds into the
fault, eighty-eight requests hit nginx's `proxy_read_timeout` **in the same second** — they had all
started within a second of each other — spread across all three upstreams. Every replica crossed
`max_fails` simultaneously. nginx logged `no live upstreams` and answered `502 Bad Gateway`, with
no `Retry-After`, to 1,227 buyers, for nine seconds, while all three replicas were up and
answering correctly to anything that reached them.

Full account: [the postmortem](../incidents/2026-09-20-connection-pool-exhaustion.md).

## Decision

**Disable nginx's passive ejection (`max_fails=0`) and let the container health check own
liveness.**

Supporting changes, each measured:

- `proxy_read_timeout` on the API location drops from 30 s to 10 s, above every ceiling the
  application now has and below anything a buyer would wait through.
- `lock_timeout` of 2 s on application connections, so a statement blocked in PostgreSQL gives up
  and releases its pool connection rather than being held until the proxy loses patience.
- `55P03` and `57014` translate to `CannotAcquireLockException` and `QueryTimeoutException`, which
  the error handler already answers `503` with `Retry-After` for.
- `proxy_next_upstream error` only. `timeout` is removed: retrying a timed-out request during a
  shared slowdown adds load to a service that is already shedding.

## Why this and not the alternatives

**Raise `max_fails` instead.** Tempting and wrong in kind rather than in degree. Any threshold
that a single replica can cross, three replicas sharing a database can cross together — the
question is only how bad the shared slowdown has to be. It converts a certainty into a
probability, and the probability is highest exactly when the service is already in trouble.

**Keep passive ejection and shorten `fail_timeout`.** Makes the outage shorter, not absent. Nine
seconds becomes three, which is still every buyer getting an error page with no retry advice while
the service works.

**Active health checks.** The right answer, and not available: nginx's `health_check` directive is
a commercial feature. The open-source equivalent is a separate discovery component polling
`/ready`, which is infrastructure this deployment would carry and the AWS deployment would not —
there, the ALB target group does exactly this, and it is what the Terraform configures.

## Consequences

**A genuinely dead replica keeps receiving traffic** until the container health check removes it.
That is a real cost and it has a measured price: killing a replica under load cost 8 failed
requests out of 18,001, and the container was gone from the pool within seconds. The failures that
scenario produced were on the *restart* — nginx sending traffic to a container that had an address
before the JVM was listening — which passive ejection would have made worse, not better, since the
new replica was ejected before it ever served a request.

**The trade is explicit:** a handful of requests during a replica's death or birth, against every
request during a shared slowdown. The first is measured at 0.04%; the second was measured at 100%
for nine seconds.

**On AWS this ADR mostly does not apply.** The ALB health-checks its targets actively and removes
them on that basis, which is the behaviour this decision is approximating. The Compose edge is a
stand-in for the ALB, and its configuration should be read as one.

## The falsifier

Re-run the pool-exhaustion game day. If the edge logs `no live upstreams` even once, this decision
is wrong or incompletely applied. It has been re-run three times since; it logs none. The scenario
is `make game-day SCENARIO=exhaust-pool`, and the check is
`docker compose logs edge | grep -c "no live upstreams"`.
