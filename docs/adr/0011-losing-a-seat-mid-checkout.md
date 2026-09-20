# ADR 0011: What a buyer sees when they lose a seat

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

Under a flash crowd, losing a seat is not an edge case; it is the common case. Most people who
click a seat will not get it. The question is what they are told, and the tempting answers are all
worse than they look:

- **Silently substitute a different seat.** The buyer chose row A and is sold row M. This is the
  worst option and it is the one that happens by default if "best available" is used as a fallback
  for a failed exact request.
- **Say "something went wrong".** True and useless. The buyer does not know whether to retry, pick
  again, or leave.
- **Say nothing and let the map update.** The seat greys out, the total silently changes, and the
  buyer discovers it at the payment screen.

There are three distinct moments at which a seat can be lost, and they need different answers.

## Decision

**1. Lost between render and click — the seat was already gone.**
The request is refused with `409 seat_unavailable` naming the seat. The map updates that seat in
place, the selection drops it, and the message names it: *"Someone else took A-12. Those seats are
no longer selected."* No substitution, ever. The buyer's other selected seats are kept.

**2. Lost while holding, before checkout started — the hold expired.**
The countdown reaches zero, the hold is cleared, and the message is *"Your hold expired and the
seats went back on sale. Choose again if they are still free."* It is announced assertively as
well as shown, because a screen-reader user staring at a payment form needs to know the form is
now pointless. The seats are not silently re-held: they may be gone, and pretending otherwise
would set up the same disappointment one step later.

**3. Lost during checkout, after payment was attempted.**
The hold is extended when checkout begins, to cover the payment window, so this is rare. When it
happens anyway and the money moved, the order is marked `seats_lost_after_payment`, an error is
logged for a human, a counter increments, and the buyer is told plainly: *"Your payment went
through but the seats were released first. A refund has been queued."* This is the only message in
the product that admits a failure the buyer cannot act on, and it says what will happen rather
than apologising.

**Also decided:** a gateway timeout is not a loss. The seats stay held, the buyer is told to press
Pay again, and the retry carries the same `Idempotency-Key` so it completes the original charge
rather than making a second one.

## Alternatives considered

- **Offer an automatic substitute of equal or better value.** Rejected for now. It is a reasonable
  product behaviour and several real services do it, but it needs the buyer's prior consent to
  mean anything, and consent collected in a modal during a flash sale is not consent. Revisit with
  a "would you accept nearby seats?" checkbox set before the crowd arrives.
- **Hold the seats through a payment failure so the buyer can retry with another card.** Rejected:
  it lets a buyer with a dead card hold seats for the full TTL while others are refused. A decline
  releases immediately; a timeout does not, because a timeout might mean success.
- **Extend the hold automatically when it is about to expire.** Rejected: unbounded, and it
  converts a fair queue into a reward for leaving a tab open.

## Consequences

- Every one of these paths needs a distinct error code, which is why `ErrorCode` distinguishes
  `seat_unavailable`, `sold_out`, `hold_expired`, `hold_not_active`, `payment_declined` and
  `payment_timeout` rather than collapsing them into 409 and 410.
- `seats_lost_after_payment` is a metric with an alert on it, not a log line. If it is ever
  non-zero in normal operation, the checkout grace is too short.
- The hold TTL and the checkout grace are coupled: the grace must exceed the payment timeout, or
  case 3 stops being rare. See [ADR 0012](./0012-hold-ttl.md).

## Falsifier

`purchase.spec.ts` covers case 1 end to end: a rival takes the seat between render and click, and
the test asserts both that the buyer is told which seat went and that no substitute appears. Case 2
is covered by the expiring-hold test, which asserts the checkout button disappears — it caught a
real bug where a cached buyer-state response re-adopted the expired hold and offered Pay for seats
already back on sale. Case 3 is covered in the integration suite by forcing the hold to resolve
between prepare and settle, and asserting the order ends `FAILED` with the refund code rather than
confirming.
