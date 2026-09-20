# ADR 0007: "Released" is an outcome of a hold, not a state of a seat

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

The scope lists the seat lifecycle as `available -> held(expiry) -> sold`, "plus released and
blocked". Modelling `RELEASED` as a fifth seat status is the literal reading.

## Decision

The `seats.status` enum is `AVAILABLE`, `HELD`, `SOLD`, `BLOCKED`. Release is recorded on the
hold, whose status becomes `CANCELLED` or `EXPIRED`, and the seat returns to `AVAILABLE`.

## Alternatives considered

- **`RELEASED` as a seat status.** Rejected on two grounds. First, it carries no information a
  buyer can act on: a released seat and a never-held seat are the same thing to somebody trying
  to buy it, so every query would immediately have to write `status in ('AVAILABLE','RELEASED')`
  and one of those spellings would eventually be forgotten. Second, it doubles the number of
  legal transitions the tests have to cover in exchange for nothing.
- **A `released_at` column on the seat.** Rejected as a duplicate of `holds.resolved_at`, which
  already records who released it and why.

## Consequences

- The audit trail for "why did this seat come back" lives on the hold, not the seat. The hold
  table is append-only in practice — resolved rows are never deleted — so the trail is complete.
- The reference model in the property test has three seat states, not five, which is part of why
  it is small enough to be read and believed.

## Falsifier

The `seats.status` check constraint in `V002__catalog.sql` rejects any other value, so a stray
`RELEASED` write fails at the database rather than creating a state nothing queries for.
`ReferenceModel` enumerates the same three reachable states and the model-based property test
fails if the service ever produces a fourth.
