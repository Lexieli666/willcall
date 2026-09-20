# 2026-09-20 — killing a replica under load

- **Type:** game day
- **Severity:** 8 failed requests out of 18,001 (0.04%), all of them during the *restart*, none
  during the kill
- **Duration:** 41 s between kill and restart; the failures occupy about 3 s of it
- **Author:** Lexie Li

## What happened

`app2` was killed outright while 150 hold requests a second flowed through the edge, and restarted
41 seconds later. Killing it cost nothing measurable. Restarting it cost eight requests, because
nginx began sending traffic to the new container the moment it had an address, several seconds
before the JVM was listening on port 8080.

The prediction was about what a kill does. The measurement is about what a start does.

## Prediction made beforehand

Copied from `docs/game-day.md`, unedited:

> **Hypothesis.** The proxy removes it within `fail_timeout` (5 s) and in-flight requests to it
> fail. SSE connections on that replica die and reconnect to the survivors, each costing a
> snapshot, so open-connection counts on app1 and app3 jump by roughly half of app2's share each.
> No oversells, because a killed replica cannot half-commit a transaction. The restarted replica
> comes back with zero connections and stays near zero, because nothing rebalances long-lived
> streams.
>
> **What would be a surprise.** Holds failing on the surviving replicas; the sweeper stopping; the
> restarted replica taking traffic but not connections and nobody noticing.

"No oversells" held. "In-flight requests to it fail" was not visible: whatever was in flight at
21:55:09 either completed or was retried invisibly, and no error appeared for 41 seconds.

## Timeline

All times UTC.

| Time | Event |
|---|---|
| 21:54:28 | Load starts: 150 hold requests/s, three replicas |
| 21:55:09 | `docker compose kill app2` |
| 21:55:09–21:55:50 | Two replicas serve the full rate. No errors logged at the edge, no `503`s, no `500`s |
| 21:55:50 | `docker compose up -d app2` |
| 21:55:50 | nginx: `connect() failed (111: Connection refused)` to the new container, three times |
| 21:55:50 | nginx: `upstream server temporarily disabled` — the new replica is ejected before it ever served a request |
| 21:55:53 | Two more refusals, another ejection |
| 21:55:55 approx. | The JVM finishes starting; traffic resumes to all three |
| 21:56:28 | Load ends. 17,810 holds granted, 8 failures |
| 21:56:30 | `verify-invariants.sh`: 7/7 pass |

## Detection

The counter reset on `app2` — 26 series going backwards in one sample — is unambiguous evidence
that the process died, and it is what the summariser now uses to confirm the fault landed at all.

Nothing detected the restart failures. They are eight requests in eighteen thousand; no threshold
would fire on that, and none should. They were found by reading the edge log, which at the time
was only being read because a different scenario had gone wrong.

## Root cause

**A container has an address before the application inside it can answer.** nginx resolves the
upstream by name at start-up and holds the address; when Docker recreates the container it comes
back reachable at the same address, and nginx starts sending requests to a port nothing is
listening on. There is a Docker health check on the application and it works, but nginx does not
consult it — passive upstream health and container health are two separate systems that do not
talk to each other.

## What made it worse, and what made it better

**Worse:**

- The requests that failed were answered `502` by the edge, which the application never sees, so
  they appear in no application metric. Same blind spot as the pool-exhaustion incident, found the
  same way, on the same day.

**Better:**

- Two replicas absorbed the full 150/s with a p99 of 14.8 ms, so losing a third of the capacity was
  invisible to buyers. That is the property the capacity headroom exists to buy.
- `SKIP LOCKED` in the sweeper meant expiry kept running on the survivors with no leader election
  to re-elect. Nothing needed to notice the death.

## Impact

Measured from `load/results/2026-09-20/gameday-kill-replica-215428/`.

| Quantity | Measured |
|---|---|
| Requests during the run | 18,001 |
| Holds granted | 17,810 |
| Failures | 8 (0.04%) |
| `500`s | 0 |
| `503`s | 0 |
| Hold latency, median / p99 | 7.5 ms / 14.8 ms |
| Hold latency, max | 38,664 ms — one request, discussed below |
| Counter series reset on `app2` | 26 (the process died) |
| Oversells | 0 |
| Invariant checks after the scenario | 7/7 pass |

**The 38.7 s outlier is not explained.** One request out of 18,001 took a little less than the
41 s between kill and restart, which is suggestive but not a mechanism. With `proxy_next_upstream
error` and one try it should have failed immediately when its upstream went away. What would
settle it: per-request timing at the edge, which the access log now records (`request_time` and
`upstream_time`), and which was off when this run happened. It is written down as unexplained
rather than attributed to the obvious-looking cause.

## What was wrong in our understanding

- **The hypothesis treated the kill as the dangerous half.** The restart is the dangerous half. A
  replica that is gone is simply gone; a replica that is present and not ready is worse, because
  the proxy believes in it.
- **"The health check will handle it" was not true of the component that needed it.** The health
  check governs Docker's opinion of the container. nginx has its own opinion and forms it by
  sending real traffic.

## Actions

| # | Action | Kind | Owner | Status |
|---|---|---|---|---|
| 1 | Turn the edge access log on with `request_time` and `upstream_time`, so a 502 or a 38-second request is visible without reading an error log | detect | Lexie Li | done — see the pool-exhaustion postmortem |
| 2 | Keep the counter-reset check in the game-day summariser; it is the only positive evidence that a kill scenario killed anything | detect | Lexie Li | done |
| 3 | Gate the upstream on readiness rather than on reachability — nginx Plus has active health checks, and the open-source path is an explicit `/ready` poll or moving the edge to a load balancer that reads container health | prevent | Lexie Li | not done; see below |
| 4 | Re-run this scenario and confirm the restart window is clean | detect | Lexie Li | not done |

Actions 3 and 4 are **not done**. Action 3 is a change to how the edge discovers replicas and is
not a configuration tweak; on AWS it is the ALB target group's health check, which is the
deployment this repository has Terraform for and no credentials to raise. Doing it properly in the
Compose stack means adding a discovery component that the AWS deployment would then not use. It is
recorded as understood and unfixed rather than papered over, and the eight requests it costs are
stated above.

## Evidence

- Raw result directory: `load/results/2026-09-20/gameday-kill-replica-215428/`
- `metrics.txt` — the 26 counter series on `app2` that return to zero at 21:55:09
- Edge log at 21:55:50: `connect() failed (111: Connection refused)` and
  `upstream server temporarily disabled`, both naming `172.19.0.5` — the restarted container
