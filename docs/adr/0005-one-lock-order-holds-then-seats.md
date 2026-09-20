# ADR 0005: One lock order for the whole reservation core — holds, then seats, both ascending by seat id

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

Four operations change seat state: acquire, confirm, cancel, and sweep. Three of them touch both
the `holds` table and the `seats` table. Any two that take the same pair of row locks in opposite
orders will eventually deadlock, and under a flash sale "eventually" means within seconds.

The first draft had acquire taking seat locks and then hold locks, while confirm took hold locks
and then seat locks. That is a textbook deadlock cycle.

There is a second, subtler ordering question. A request for three seats takes three seat locks.
Two overlapping multi-seat requests that lock in different orders deadlock even though neither
touches the `holds` table first.

## Decision

**Hold rows first, then seat rows. Within each, ascending by seat id.**

- `confirm`, `cancel` and the sweeper all begin with a `SELECT ... FROM holds ... ORDER BY
  seat_id FOR UPDATE`, then touch seats.
- `acquire` locks seats — `ORDER BY id FOR UPDATE` — and then *inserts* hold rows. It never waits
  on an existing hold row, so it cannot participate in a cycle with the other three. A concurrent
  acquire for the same seat blocks at the seat lock, sees `HELD`, and returns 409.
- Ordering is always applied by PostgreSQL inside the statement that takes the lock, never by
  sorting in Java and then locking in a loop.

**"Ascending by seat id" means PostgreSQL's order, not Java's.** `UUID.compareTo` compares the
most and least significant bits as *signed* longs, so every UUID with the top bit set sorts before
every UUID without it. PostgreSQL compares uuid values as sixteen unsigned bytes. The two disagree
on roughly half of all pairs. `SeatOrdering.ASCENDING` is the single comparator that matches the
database, and Java-side sorting uses it everywhere.

## Alternatives considered

- **Expire holds lazily, when a reader notices the seat.** Rejected: that would take a hold lock
  while already holding a seat lock, which is the reverse order and therefore the deadlock this
  ADR exists to prevent. The background sweeper at a 250 ms tick achieves the same freshness with
  one lock order.
- **`SELECT ... FOR UPDATE SKIP LOCKED` everywhere.** Rejected for named-seat acquisition: a
  buyer who asked for seat H-12 and silently got a different seat because H-12 was momentarily
  locked has been lied to. `SKIP LOCKED` is correct for "any free seat" and for the sweeper, and
  both use it.
- **A single global advisory lock per event.** Rejected: it serialises the entire event, which is
  precisely the throughput the flash sale needs.
- **Retry on deadlock.** Rejected as a primary strategy. Deadlock retries turn a design problem
  into a latency problem that only appears under the load you least want it under.

## Consequences

- Any new operation that touches both tables must follow this order. The rule is stated at the
  top of `SeatRepository` and `HoldRepository`, where somebody writing such an operation will be.
- Named-seat acquisition can block on a contended seat rather than failing fast. That is the
  intended trade: the wait is bounded by the lock timeout and the answer is truthful.
- `SeatOrdering` must be used for every Java-side sort of seat ids. Sorting with the default
  comparator would not break anything today — every lock is ordered inside its own query — but it
  would make a future "lock these in a loop" implementation silently wrong.

## Falsifier

`ConcurrentHoldIntegrationTest` fires 10,000 simultaneous acquisitions at 500 seats and counts
unexpected failures separately from clean 409s; a deadlock shows up as a non-zero unexpected
count, not as a passing test. `ChaosIntegrationTest` runs acquire, confirm, cancel and the sweeper
concurrently for the same reason. `SeatOrderingTest` pins the signed-versus-unsigned difference
with the exact UUID pair that exposes it.
