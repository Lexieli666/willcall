# ADR 0001: PostgreSQL owns correctness; Redis only accelerates

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

Willcall has one hard requirement — no oversells — and one soft one — a fair, responsive
waiting room under a flash crowd. The two pull in different directions. The fair-queue and
rate-limiting data structures want an in-memory store with cheap sorted-set operations; the
seat inventory wants serialisable, durable, crash-safe allocation.

A single store for both would mean either running the seat inventory in Redis, where a
partition or an eviction can silently lose an allocation, or running the waiting room in
PostgreSQL, where every position poll becomes a write-amplifying transaction.

## Decision

PostgreSQL 16 is the sole source of truth for anything whose loss would be a correctness bug:
seats, holds, orders, idempotency records, and the outbox. Every state transition on a seat
happens inside one PostgreSQL transaction that takes a row lock on the seat before writing,
and the schema carries partial unique indexes that make a double allocation impossible even if
the application logic is wrong.

Redis 7 holds only data whose loss degrades the experience without corrupting it: the
waiting-room sorted set, admission-token bookkeeping, rate-limit counters, a read-through cache
of idempotent responses, and the pub/sub bus that fans seat deltas out across replicas.

If Redis is unavailable the service must keep selling seats correctly. Admission falls back to
open admission, position reporting degrades to "unknown", and delta fan-out falls back to each
replica's own events plus client resync.

## Alternatives considered

- **Redis as the inventory store with Lua scripts for atomicity.** Rejected: single-instance
  Redis is not durable across a crash by default, and the failure mode is an oversold event,
  which is exactly the failure the project exists to prevent.
- **PostgreSQL for the waiting room too.** Rejected on write cost: a 10,000-entrant queue
  polling position every two seconds is 5,000 reads/s against the same database that is
  serving holds, competing for the connection pool that the hold path needs.
- **A distributed lock service (etcd, ZooKeeper).** Rejected as unnecessary: the lock the
  system needs is already a row lock, and adding a third stateful dependency adds a failure
  mode without removing one.

## Consequences

- Hold throughput is bounded by PostgreSQL row-lock contention on hot rows, not by Redis. The
  capacity model must therefore be expressed in terms of database behaviour, and the named
  bottleneck in `docs/capacity-model.md` is expected to be on the database side.
- Every Redis call site must have a defined behaviour when Redis is down, and that behaviour
  must never be "fail the reservation".
- A Redis restart in the middle of a load run is a supported scenario, not an outage.

## Falsifier

`RedisOutageInvariantIntegrationTest` stops Redis mid-run while holds and confirmations are in
flight, restarts it, and then runs the full invariant suite from `scripts/verify-invariants.sh`
against the resulting database. If Redis loss can corrupt inventory, that test fails.
