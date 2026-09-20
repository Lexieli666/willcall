# ADR 0012: The hold TTL is 120 seconds, and that number is not yet evidence

- **Status:** **proposed — the final value needs data this build cannot produce**
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

The hold TTL is the single most consequential product number in the system. It trades two failures
against each other:

- **Too short** and buyers who are reading, deciding, or fetching a card from another room lose
  seats they were actively trying to buy. Every one of those is a lost sale *and* a released seat
  that churns through the map, costing deltas and confusing everyone watching.
- **Too long** and the inventory is locked up by people who have wandered off. During a sell-out
  the queue stalls behind holds that will never convert, and the measured time-to-sell-out
  lengthens by roughly the TTL.

There is no way to pick correctly from first principles. The right value depends on how long real
people actually take, which is a distribution nobody in this project has yet observed.

## Decision

**The default is 120 seconds, and it is a starting point, not a finding.**

It is configurable per event (`events.hold_ttl_seconds`, 15–3600) because the right value depends
on the checkout the buyer faces, and a single global constant would be wrong for every event that
is not the median one.

A **checkout grace** of 30 seconds extends the hold when payment begins, so a slow card cannot
cost a buyer their seats between prepare and settle. The grace must exceed the payment timeout, or
[ADR 0011](./0011-losing-a-seat-mid-checkout.md) case 3 — money taken, seats gone — stops being
rare. With a 2 s gateway timeout and a 30 s grace there is a wide margin.

### What would turn 120 into a finding

The measurement that decides this is the distribution of *time from hold granted to checkout
submitted* among real buyers under real time pressure. Specifically:

| Quantity | Why it decides the TTL |
|---|---|
| p50, p90, p99 of hold-to-checkout | The TTL should sit above p90; above p99 wastes inventory |
| Expiry rate, split by "expired then re-bought" vs "expired and left" | The first is annoyance, the second is a lost sale |
| Time-to-sell-out at two different TTLs | Quantifies what a longer TTL costs the queue |
| Abandonment rate after an expiry | Whether losing a hold makes people give up entirely |

Synthetic load cannot produce any of these. A k6 virtual user checks out in the milliseconds the
script tells it to; it does not hesitate, re-read the price, or go and find a wallet. Running the
load tests and then publishing a TTL "validated under load" would be measuring the load script's
sleep values.

## Status of the evidence

> **Pending — needs the public demo with real users.**
>
> Until that demo has run and its hold-to-checkout distribution has been analysed, this ADR stays
> `proposed`, the 120-second default stands as an unvalidated assumption, and no claim is made
> anywhere in this repository that the TTL was chosen from data. `PROGRESS.md` tracks it as an
> outstanding human item.

## Alternatives considered

- **Pick a value from what other ticketing services use** (commonly 2–10 minutes). Rejected as
  evidence: their checkout, their audience and their inventory pressure are not this one's. Useful
  as a sanity check on the default, which is why 120 s is inside that range rather than outside
  it.
- **Adaptive TTL, shortening as inventory runs low.** Attractive and rejected for now: it is a
  policy that punishes the people at the back of the queue for being at the back, and it cannot be
  tuned without the same distribution this ADR is waiting for.
- **No TTL; hold until the buyer leaves.** Rejected: a closed tab would hold a seat forever.

## Falsifier

The correctness of the *mechanism* is tested: `ReservationCoreIntegrationTest` asserts that expiry
releases capacity exactly once and that a second sweep changes nothing, and the chaos test asserts
that abandoned checkouts do not leak capacity. The correctness of the *number* is not tested,
because it cannot be, and this document says so rather than implying otherwise.
