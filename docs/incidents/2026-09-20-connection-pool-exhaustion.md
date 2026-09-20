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
| 1 | Set `lock_timeout` on application connections so a blocked statement fails and releases its pool connection instead of waiting for the proxy to give up | prevent | Lexie Li | pending |
| 2 | Map PostgreSQL `55P03` (lock not available) and `57014` (query cancelled) to `503` with `Retry-After`, like pool exhaustion | prevent | Lexie Li | pending |
| 3 | Raise `max_fails` and shorten `proxy_read_timeout` on the API location so shared slowness cannot eject every replica at once | mitigate | Lexie Li | pending |
| 4 | Stop retrying timed-out requests on the next upstream; during a shared slowdown it is pure amplification | mitigate | Lexie Li | pending |
| 5 | Turn the edge access log back on, in a format that counts status codes, so a `502` the application never sees is still counted somewhere | detect | Lexie Li | pending |
| 6 | Alert on edge 5xx, not only on application 5xx — the gap between them is exactly this incident | detect | Lexie Li | pending |
| 7 | Re-run this scenario and confirm the `502`s are gone | detect | Lexie Li | pending |

## Verification

**Not yet done.** The falsifier is simple and is already a scenario that runs on demand: hold the
lock again, and if the edge logs `no live upstreams` even once, the fix did not work. This section
will name the result directory once that run exists; until then every action above is pending and
this postmortem describes a diagnosis, not a repair.

## Evidence

- Raw result directory: `load/results/2026-09-20/gameday-exhaust-pool-215700/`
- `metrics.txt` — the sampled `hikaricp_connections_pending` series and per-status counters
- `timeline.txt` — fault injection and release times
- Edge log excerpt: `upstream timed out (110: Operation timed out) while reading response header`
  at 21:58:10, then 1,227 × `no live upstreams while connecting to upstream`
