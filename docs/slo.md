# Service level objectives

An SLO is a promise with a consequence. Each one below states what is promised, what is measured,
where the measurement comes from, and what happens when the budget is spent. An objective with no
error budget and no consequence is a target, and targets are in `docs/capacity-model.md`.

Every threshold here is derived from a measurement in `load/results/`, not chosen because it
looked round.

## The service level indicators

| # | Indicator | Objective | Window | Measured from |
|---|---|---|---|---|
| 1 | **Availability of the reservation path** — the share of hold and checkout requests answered with something other than 5xx | 99.9% | 30 days rolling | the **edge** access log by status, not the application's own counters — see below |
| 2 | **Hold latency** — time to answer a hold request | p99 ≤ 250 ms | 30 days rolling | `http_server_requests_seconds` on the hold endpoint |
| 3 | **Checkout latency** — time to answer a confirm | p99 ≤ 2 s | 30 days rolling | `http_server_requests_seconds` on `/api/orders`, with the gateway's configured latency stated |
| 4 | **Delta propagation** — commit to the frame reaching a browser | p99 ≤ 300 ms | 7 days rolling | the load scenario; there is no production measurement of this yet, which is stated below |
| 5 | **Correctness** — oversells | **zero, no budget** | always | `scripts/verify-invariants.sh`, in CI, after every load run, and hourly against the deployed database |
| 6 | **Queue fairness** — FIFO inversion rate among admitted buyers | ≤ 2% | per event | `admissions` table, computed in SQL |

### Why these numbers

**1. 99.9% availability, not 99.99%.** The measured flash-sale behaviour sheds load as 503 when the
connection pool saturates, and a 503 counts against this objective. Promising four nines would mean
promising never to shed, which the capacity model says is false above a measured 200 requests per
second on three replicas of this size. Three nines is 43 minutes a month, which is about one
badly-paced drop.

**Measured at the edge, and that is a correction.** This indicator originally read
`http_server_requests_seconds_count`, which is what the application counts. The game day on
2026-09-20 produced 1,227 responses with status `502` that the application never saw, because the
proxy generated them after ejecting every replica — so by the application's own metrics that
outage did not happen. An availability objective measured behind the thing that was unavailable is
not an availability objective. The edge access log now records status and timing, and this
indicator is read from there. See
[the postmortem](incidents/2026-09-20-connection-pool-exhaustion.md).

**Not yet wired.** The edge records status and timing in its access log; nothing turns that into a
time series in this stack. On AWS the ALB's `HTTPCode_ELB_5XX_Count` is the same signal and needs
no pipeline. Until one of those exists, this indicator can be computed after the fact from the log
and not evaluated continuously — which is stated here rather than left to be discovered.

**2. Hold p99 ≤ 250 ms** rather than the 150 ms the plan targets. The plan's figure is for a
controlled thousand requests per second; the SLO has to survive a real drop, where the measured p99
under an unpaced ten-thousand-buyer burst was 3.4 seconds. 250 ms is the promise for *paced*
traffic, which is what the waiting room exists to produce — and the admission rate is set from the
measurement that makes it true.

**3. Checkout p99 ≤ 2 s** with a 50 ms gateway. The gateway's latency is part of the number and is
stated wherever the number appears; a checkout SLO that hides the payment processor's contribution
is a promise about something the service does not control.

**4. Propagation p99 ≤ 300 ms**, against a measured 223 ms at five thousand connections. The gap is
deliberate headroom, because the measurement was taken with the load generator sharing a host and
no production measurement exists yet. **This indicator is not yet instrumented in production** —
the server publishes `willcall_sse_flush_duration`, which is the server's share only. Closing that
gap means having clients report receipt time, and it is listed as outstanding rather than quietly
treated as covered.

**5. Zero oversells, with no error budget.** Every other objective here trades against cost. This
one does not: an oversell is a person turned away at a door holding a ticket they paid for. The
consequence of a single occurrence is in the response section below.

**6. FIFO inversion ≤ 2%.** Admission happens in batches across replicas, so strict ordering is not
something the architecture provides — see
[ADR 0010](adr/0010-fairness-policy.md). The objective bounds how far from arrival order admission
may drift, and the measured rate is published per event whatever it is.

The measurement counts strictly, on the timestamps, and not on a rank. Admission is batched — the
run of record admitted 16,065 buyers at 316 distinct instants — and ranking within a batch breaks
ties on a random identifier, which invents an ordering and then counts it as unfairness. Two people
admitted in the same batch were not admitted before or after each other. **The batch size is
therefore part of the objective**: order is honoured between batches and undefined within one, and
the largest batch in the run of record was 100.

## Error budgets and what spending one means

| Objective | Budget over 30 days | At 25% | At 100% |
|---|---|---|---|
| Availability 99.9% | 43 minutes | Note it in the weekly review | Feature work stops until the cause is fixed |
| Hold p99 ≤ 250 ms | 1% of requests slower | Capacity review | Lower the admission rate; re-measure before raising it |
| Checkout p99 ≤ 2 s | 1% of requests slower | Check the gateway's own latency first | As above |
| Propagation p99 ≤ 300 ms | 1% of deltas slower | Review the coalescing window and relay tick | Reduce the connection ceiling per replica |
| **Oversells** | **none** | — | Sales stop for the affected event. The invariant is the product. |
| FIFO inversion ≤ 2% | — | Re-measure the admission batch size | Reduce the batch size, accepting lower throughput |

The oversell row is the only one whose response is "stop selling". That asymmetry is the point:
every other failure here is a degradation a buyer can live with, and that one is a promise broken
to somebody who already paid.

## Alerts

Alerts fire on **symptoms a person can act on**, not on every threshold. An alert nobody can act on
teaches people to ignore alerts, which costs more than the thing it was watching.

Every row says whether it is **wired** — defined in
[`infra/observability/prometheus/alerts.yml`](../infra/observability/prometheus/alerts.yml) and
evaluated by Prometheus — or **specified**, meaning the condition is agreed and the signal it needs
does not exist in this deployment. Writing an alert down is not the same as having one, and the
difference is exactly the sort of thing that is discovered during an incident.

| Alert | Condition | Severity | Wired | Why a person is needed |
|---|---|---|---|---|
| `WillcallOversellDetected` | `max(willcall_invariant_violations) > 0` | **page** | yes | The invariant is broken. Nothing automated should try to fix inventory. |
| `WillcallInvariantCheckStale` | no check completed in 10 minutes, or none since start-up | **page** | yes | The alert above is only as good as the check behind it. A gauge that nobody is updating reads as "no violations". |
| `WillcallSeatsLostAfterPayment` | `willcall_orders_seats_lost_after_payment_total` increases | **page** | yes | Somebody paid and has no seat. A refund needs issuing. |
| `WillcallReservationErrors` | 5xx rate on the hold or order path > 1% for 5 minutes | **page** | yes | The service is failing, not shedding — 503 is excluded deliberately. |
| `WillcallSheddingSustained` | 503 rate > 10% for 10 minutes | ticket | yes | The admission rate is set too high for the measured capacity. |
| `WillcallPoolSaturated` | `hikaricp_connections_pending` > 0 for 5 minutes | ticket | yes | The bottleneck is being reached; the capacity model needs revisiting. |
| `WillcallSweeperStalled` | `willcall_holds_expired_total` flat while `willcall_holds_granted_total` rises, 10 minutes | **page** | yes | Capacity is leaking. Every expired hold that is not released is a seat nobody can buy. |
| `WillcallOutboxBacklog` | unpublished outbox rows > 10,000 for 5 minutes | ticket | yes | Deltas are not reaching browsers; the seat map is going stale. |
| `WillcallQueueDegraded` | `willcall_queue_degraded_total` increases | ticket | yes | Redis is unreachable and admission is open. Selling correctly but unpaced. |
| `WillcallRateLimiterFailedOpen` | `willcall_ratelimit_failed_open_total` increases | ticket | yes | The limiter is not limiting. Not urgent alone; urgent with the one above. |
| `WillcallEdgeErrors` | edge 5xx rate > 1% for 2 minutes **while application 5xx stays flat** | **page** | **no** | The gap between the two is the proxy failing on its own. This is the alert that would have fired during the game day, and nothing else would have. |
| `WillcallNoLiveUpstreams` | the edge logs `no live upstreams`, any occurrence | **page** | **no** | Every replica has been ejected. It happened once, for nine seconds, with all three replicas healthy. |

**The two edge alerts are specified and not wired**, and they are the two that would have caught
the worst incident this project has had. nginx's open-source build exports no per-status counters —
`stub_status` has connection counts and nothing about response codes — so there is no metric for
Prometheus to evaluate. The access log now records status and timing, which makes the outage
*visible after the fact*; turning that into an alert needs a log pipeline this stack does not have.
On AWS the signal exists and is free: the ALB publishes `HTTPCode_ELB_5XX_Count`, which is precisely
"the proxy failed on its own", and that is where these two belong. Recorded as a gap rather than
implemented badly.

**Deliberately not alerted on:** the 409 rate, which is the product working during a sell-out, and
the gap-and-resync counters, which are the protocol recovering as designed. A gap count that stays
at zero during a burst usually means the counter is broken, not that nothing was lost.

## What is not covered

Stated so its absence is a decision rather than an oversight:

- **No production measurement of propagation.** Indicator 4 is measured by the load scenario only.
- **No availability measurement from outside the service.** Everything here is measured by the
  service about itself, so an outage that stops it serving also stops it reporting. External
  probing is the gap, and it is the first thing to add.
- **No SLO on the waiting room's own latency.** Joining is fast in the measurements, but nobody has
  decided what "too slow to join" means to a buyer, and inventing a number for this document would
  be exactly the kind of unmeasured figure the repository rules forbid.
