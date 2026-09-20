# 2026-09-20 — the edge turned a shed into an outage

- **Type:** game day
- **Severity:** every buyer, for nine seconds, received `502 Bad Gateway` with no `Retry-After`,
  while all three application replicas were running and answering correctly
- **Duration:** 40 s of degradation, of which 9 s was a total edge outage
- **Author:** Lexie Li

## What happened

An exclusive lock was taken on the seat table to starve the connection pool, which is the failure
the flash sale hit for real earlier in the project. The application behaved as designed: it shed
4,427 requests as `503` with `Retry-After` and returned not one `500`. Thirty seconds into the
fault, nginx ejected all three replicas from its upstream pool within the same second and began
answering `502 Bad Gateway` to everybody. For nine seconds the service was unreachable through the
front door while every replica behind it was healthy and shedding politely.

The prediction was about the application. The application was fine. The proxy was the outage.

## Prediction made beforehand

Copied from `docs/game-day.md`, unedited:

> **Hypothesis.** The pool saturates, `hikaricp_connections_pending` rises, and requests answer 503
> with `Retry-After` rather than 500 — that mapping was added after the first flash-sale suite
> reported nearly two thousand 500s. The `WillcallPoolSaturated` alert fires within five minutes.
> Buyers see "the service is at capacity", not an error page. No oversells: a transaction that
> never started cannot corrupt anything.
>
> **What would be a surprise.** Any 500s. A stalled sweeper that never recovers after the pool
> frees up. Holds succeeding while checkouts fail in a way that leaves orders PENDING forever.

Everything in the hypothesis held. The list of surprises was the wrong list: it contained only
things the application could do wrong, and the failure was one layer up.

## Timeline

All times UTC.

| Time | Event |
|---|---|
| 21:56:00 | Load starts: 150 hold requests/s through the edge, three replicas |
| 21:57:40 | `lock table seats in access exclusive mode` taken by an external session |
| 21:57:42 | `hikaricp_connections_pending` rises to 259 across the three replicas within one sample |
| 21:57:44 | First `503`s — 173 in a two-second window, four seconds after the fault |
| 21:57:44–21:58:23 | Steady shedding at roughly 150/s, the entire offered rate; peak 304 waiting |
| 21:58:10 | Eighty-eight requests hit nginx's 30 s `proxy_read_timeout` in the same second, spread across all three upstreams |
| 21:58:10 | nginx ejects all three replicas (`max_fails=3 fail_timeout=5s`) and logs `no live upstreams` |
| 21:58:10–21:58:19 | 1,227 requests answered `502 Bad Gateway`, no `Retry-After` |
| 21:58:19 | `fail_timeout` elapses; replicas readmitted |
| 21:58:20 | Lock released |
| 21:58:23 | Last `503`; waiters back to zero |
| 21:58:25 | `verify-invariants.sh`: 7/7 pass |

## Detection

The `503`s were visible immediately in `hikaricp_connections_pending` and in the status
distribution — that part worked, and it worked because the earlier flash-sale outage led to the
gauge being put on the dashboard.

**The `502`s were not visible anywhere.** They never reached the application, so no application
metric counted them, and `access_log` is off at the edge, so nothing counted them there either.
They were found by reading `docker logs` after the run — which rule 3 of the game day
("watch the dashboard, not the logs") says is a finding about the dashboard, not about the reader.
For nine seconds the dashboard showed a service shedding load correctly and nothing else.

## Root cause

A chain, and the first two links are the ones worth fixing.

1. **A transaction that has already taken a pool connection can block in PostgreSQL forever.**
   HikariCP's `connection-timeout` of 2 s bounds *acquiring* a connection, which is why the `503`s
   came back in 2 s. It says nothing about a statement that is already running. No `lock_timeout`
   or `statement_timeout` was set, so requests that held a connection when the lock landed waited
   for the lock indefinitely.
2. **nginx's patience was longer than anybody's.** `proxy_read_timeout 30s` on the API location
   meant those requests occupied a proxy connection for a full thirty seconds and then timed out
   *together*, because they had all started within a second of each other.
3. **nginx counts a read timeout as a replica failure.** `max_fails=3 fail_timeout=5s` is a
   reasonable setting for independent replicas failing independently. When the cause is shared —
   the database is the shared thing — all three cross the threshold in the same second, and
   passive health checking turns a degraded service into an unreachable one.

The mechanism is therefore not "the pool was exhausted". It is that **a shared dependency makes
per-replica failure detection fire on every replica at once**, and the system had nothing to
distinguish "this replica is broken" from "this replica, like the other two, is waiting on the
database".

## What made it worse, and what made it better

**Worse:**

- `proxy_next_upstream error timeout non_idempotent` meant a timed-out hold was retried against
  another replica, adding load to a service that was already shedding. The idempotency layer made
  this safe — the duplicate arrives with the same `Idempotency-Key` and is answered `409` with
  `Retry-After` — but safe is not the same as free.
- `access_log off` at the edge. It was turned off for the 5,000-connection SSE run, where logging
  every frame-carrying request was measurable overhead, and never turned back on.

**Better:**

- Mapping pool exhaustion to `503` with `Retry-After`, which was added after the flash-sale
  outage, did exactly what it was added to do: zero `500`s in 18,001 requests.
- `SKIP LOCKED` on the sweeper meant it stepped over the locked rows rather than joining the
  queue, so expiry resumed the moment the lock cleared with no backlog.
- The invariant held throughout, which is the property the whole design is for.

## Impact

Measured from `load/results/2026-09-20/gameday-exhaust-pool-215700/`.

| Quantity | Measured |
|---|---|
| Requests during the run | 18,001 |
| Holds granted | 12,221 |
| Shed as `503` with `Retry-After` | 4,427 |
| Answered `502` by the edge, application never reached | 1,227 |
| `500`s | 0 |
| Peak connections waiting for the pool | 304 across three replicas (103 on one) |
| Hold latency, median / p99 / max | 15 ms / 2,004 ms / 30,001 ms |
| Oversells | 0 |
| Invariant checks after the scenario | 7/7 pass |

The p99 of 2,004 ms is the HikariCP `connection-timeout` of 2 s, visible as a measurement. The max
of 30,001 ms is nginx's `proxy_read_timeout`, likewise.

## What was wrong in our understanding

- **`docs/capacity-model.md` named the pool as the bottleneck and was right, but described the
  failure as a fast rejection.** It is a fast rejection for requests that arrive after saturation
  and a thirty-second hang for requests that were already inside. Those are different outages and
  only one of them was written down. The capacity model now says so.
- **The game-day hypothesis assumed the blast radius ended at the application.** Every listed
  surprise was an application behaviour. Nothing asked what the proxy would do, and the proxy is
  the only component every buyer must pass through.
- **"The application sheds correctly" was treated as equivalent to "buyers get a useful answer."**
  For nine seconds it was not, and no graph would have shown the difference.

## Actions

| # | Action | Kind | Owner | Status |
|---|---|---|---|---|
| 1 | Set `lock_timeout` on application connections so a blocked statement fails and releases its pool connection instead of waiting for the proxy to give up | prevent | Lexie Li | done |
| 2 | Map PostgreSQL `55P03` (lock not available) and `57014` (query cancelled) to `503` with `Retry-After`, like pool exhaustion | prevent | Lexie Li | done |
| 3 | Stop nginx ejecting every replica when the slowness is shared: `max_fails=0`, and rely on the container health check for liveness | mitigate | Lexie Li | done |
| 4 | Shorten `proxy_read_timeout` on the API location from 30 s to 10 s, below anything the application will now take | mitigate | Lexie Li | done |
| 5 | Stop retrying timed-out requests on the next upstream; during a shared slowdown it is pure amplification | mitigate | Lexie Li | done |
| 6 | Turn the edge access log back on, with status and per-request timing, so a `502` the application never sees is still counted somewhere | detect | Lexie Li | done |
| 7 | Re-run this scenario and confirm the `502`s are gone | detect | Lexie Li | done — three times; see below |
| 8 | Shed at admission when the pool is saturated, instead of letting requests queue behind it — a bulkhead, so a `503` arrives in tens of milliseconds rather than nine seconds | mitigate | Lexie Li | **not done** |

## Verification

The scenario was re-run three times, each after a change. The falsifier is the same every time:
hold the lock again, and if the edge logs `no live upstreams` even once, the fix did not work.

| Run | Configuration | `no live upstreams` | `502` | `500` | `503` | Invariants |
|---|---|---|---|---|---|---|
| `gameday-exhaust-pool-215700` | before any fix | **1,227** | 1,227 | 0 | 4,427 | held |
| `gameday-exhaust-pool-221655` | `lock_timeout` 5 s, edge fixes | 0 | 9 | **98** | 5,274 | held |
| `gameday-exhaust-pool-222155` | + SQL exception translator | 0 | 1 | 0 | 5,402 | held |
| `gameday-exhaust-pool-222551` | `lock_timeout` 2 s | 0 | 1 | 0 | 5,530 | held |

The second run is the interesting one. `lock_timeout` worked — statements were cancelled, pool
connections were released, the edge held — and 98 requests became `500` instead of `503`, because
PostgreSQL's `55P03` arrives as an `UncategorizedSQLException`: the driver throws a plain
`PSQLException`, so Spring's subclass translator has nothing to match, and its error-code
translator has no PostgreSQL entry for that state. **Setting a timeout without deciding what its
expiry means is half a change**, and the half that was missing turned a graceful shed into a fault
for one request in sixty. It is fixed by a translator on the `JdbcTemplate` rather than another
`@ExceptionHandler`, so every query gets it and not only the ones somebody remembered.

One `502` remains in each of the later runs, out of roughly eighteen thousand requests. It is a
single request that reached the 10 s read timeout, and it is left alone rather than tuned away.

### What the verification did not fix

**Shed responses are slow.** The `503`s arrive at a p99 of 9.5 s, against 2.0 s before any of this.
That is not a regression caused by the fix: the earlier 2.0 s figure was the pool's own timeout on a
system that was simultaneously collapsing at the edge, and the requests that would have been slow
were the 1,227 that got a fast `502` instead. Lowering `lock_timeout` from 5 s to 2 s changed the
p99 from 8.3 s to 9.6 s — that is, made no useful difference — which is the measurement that says
the wait is not in the timeouts at all. It is queueing: at 150 requests/s against a database that
is not answering, the backlog ahead of the pool is what a request spends its time in, and every
timeout downstream of that queue is reached only after waiting in it.

A `503` that takes nine seconds has shed nothing a buyer still cares about. The fix is a bulkhead —
refuse at admission when the pool has no capacity, rather than accepting the request and letting it
queue — and it is action 8, recorded and not done. `lock_timeout` stays at 2 s because it bounds a
blocked statement tightly and matches the pool's own timeout, not because it made the p99 better;
it did not.

## Evidence

- Raw result directory: `load/results/2026-09-20/gameday-exhaust-pool-215700/`
- `metrics.txt` — the sampled `hikaricp_connections_pending` series and per-status counters
- `timeline.txt` — fault injection and release times
- Edge log excerpt: `upstream timed out (110: Operation timed out) while reading response header`
  at 21:58:10, then 1,227 × `no live upstreams while connecting to upstream`
- Verification runs: `load/results/2026-09-20/gameday-exhaust-pool-221655/`, `-222155/`, `-222551/`
