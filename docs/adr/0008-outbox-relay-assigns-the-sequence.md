# ADR 0008: The outbox relay assigns per-event sequence numbers, under an advisory lock

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

The real-time protocol needs a gap-free, monotonically increasing sequence number per event, so a
client can tell "I missed a message" from "nothing has happened". Seat changes are written to a
transactional outbox in the same transaction as the change itself, which is what guarantees a
delta never describes a state the database does not hold.

The question is who assigns the sequence number.

Assigning it in the writing transaction means `UPDATE events SET last_sequence = last_sequence +
1` on every hold. That is a single row, updated by every acquisition for that event, which
serialises the entire flash sale onto one row lock. It is the exact opposite of what a flash sale
needs, and it would make the per-replica capacity number a measurement of one row's contention.

## Decision

The writer appends to `outbox` with `sequence_no` null. A relay, running on every replica,
assigns the numbers:

1. Take `pg_try_advisory_xact_lock(hashtext('willcall-outbox:' || event_id))`. If another replica
   holds it, move on to a different event.
2. Read pending entries for that event in `id` order.
3. Reserve a contiguous range with a single `UPDATE events SET last_sequence = last_sequence + n
   ... RETURNING last_sequence`.
4. Stamp each entry and hand it to the fan-out.

## Alternatives considered

- **Assign in the writing transaction.** Rejected: serialises every hold for an event, as above.
- **`SELECT ... FOR UPDATE SKIP LOCKED` on the outbox instead of an advisory lock.** Rejected. It
  would let two replicas publish for the same event concurrently and assign numbers out of order.
  Every connected client would see a gap and resync — a small optimisation turned into a
  stampede, at exactly the moment the system is busiest.
- **A global sequence (the outbox's own `bigserial`).** Rejected: it is monotonic but not
  contiguous per event, so a client cannot distinguish a gap from another event's traffic.
- **Assign nothing and let clients reconcile by seat version.** Partially adopted as a safety net
  — deltas do carry `seats.version`, so a duplicate or out-of-order delta is harmless — but it
  does not solve gap *detection*, which needs a contiguous counter.

## Consequences

- One row lock per batch per event, amortised over hundreds of deltas, instead of one per hold.
- Delta delivery is asynchronous. The outbox relay tick is 100 ms by default and is part of the
  propagation-latency budget; the budget must be stated including it, not excluding it.
- The fan-out callback runs inside the relay's transaction. A delta must not reach a browser
  before the row marking it published commits, or a replica restart would replay it with a
  different number.
- A fan-out failure is logged and swallowed rather than failing the transaction. Blocking the
  sequence for everyone because one subscriber is unhappy is a worse outcome than one client
  resyncing.

## Falsifier

`ReservationCoreIntegrationTest.seatChangesAreWrittenToTheOutbox` fails if a seat change ever
commits without its outbox entry. The gap-injection test in the end-to-end suite (Phase 2) fails
if a client cannot detect and recover from a missing sequence number.
