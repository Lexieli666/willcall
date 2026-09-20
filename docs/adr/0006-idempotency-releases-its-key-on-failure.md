# ADR 0006: A failed idempotent request releases its key

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

Checkout carries an `Idempotency-Key` so a retry cannot buy twice. The obvious implementation
stores the outcome of the first attempt — success *or* failure — and replays it for every
subsequent request with the same key.

That obvious implementation breaks the single case the whole mechanism exists for.

The fake gateway has a mode called `SUCCEED_AFTER_TIMEOUT`, and it is not a curiosity: it is what
a real payment processor does when the charge lands and the response does not. The client sees a
504. It retries with the same key. If the stored 504 is replayed, the retry never reaches the
gateway, never discovers the completed charge, and the buyer is left having paid for nothing.

## Decision

- **Success is stored and replayed.** A retry after a successful confirm returns the original
  order, byte for byte, with `Idempotency-Replayed: true`.
- **An expected failure releases the key.** The next request with that key runs again.
- **A concurrent duplicate gets 409 with `Retry-After: 1`**, not a queue slot. Blocking the second
  request until the first finishes would hold a thread for the duration of a payment, and a retry
  storm would then become an outage.
- **A mismatched fingerprint gets 422.** Same key, different body is a client bug, and answering
  it with somebody else's result would tell the client a request it never made had succeeded.

Releasing on failure is safe because a failed attempt mutated nothing that rerunning would
duplicate:

| Failure | What persisted | What the retry does |
|---|---|---|
| Hold rejected (409) | nothing; the transaction rolled back | tries again, may now succeed |
| Payment declined | order `FAILED`, seats already back on sale | finds the hold gone, returns 410 |
| Gateway timeout (504) | order `PENDING`, hold extended | reuses the same order, the gateway returns the recorded charge, the order confirms |

The third row is the point. It works only because `prepareCheckout` reuses an existing `PENDING`
order for the hold group rather than minting a new order id: the gateway keys its own idempotency
on the order id, and a fresh id per attempt would defeat it and charge twice.

## Alternatives considered

- **Store and replay failures.** Rejected: strands a buyer whose charge succeeded behind a
  timeout, which is the worst outcome the system can produce.
- **Never store anything; rely on the database constraints.** Rejected: the partial unique index
  prevents a double *allocation* but not a double *charge*, and a retried confirm that finds its
  own previous order would have to guess whether it was its own.
- **Block the duplicate until the first attempt finishes.** Rejected on cost: one held thread per
  duplicate, and duplicates arrive in bursts precisely when threads are scarce.

## Consequences

- A client that retries a genuinely-doomed request retries it forever, at its own cost. Rate
  limiting is the answer to that, not idempotency.
- The key is scoped to `(buyer, endpoint, key)`. Two buyers can use the same key string without
  colliding, which matters because clients generate keys and some will use a counter.
- Records expire after 24 hours. A retry after that is a new request.

## Falsifier

`IdempotencyIntegrationTest` covers all of it: one hundred sequential replays produce one hold;
one hundred simultaneous replays produce one hold; a different body gets 422; the same key from a
different buyer is a different key; and `retryAfterGatewayTimeoutConfirmsTheCharge` fails if a
timeout is ever replayed instead of retried.
