# ADR 0003: Ship a modular monolith

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

Willcall has five cohesive concerns: event catalogue, reservation core, waiting room, real-time
fan-out, and the expiry sweeper. A natural instinct is to split them into services, because
they have different scaling shapes — the SSE hub is connection-bound, the reservation core is
lock-bound.

The reservation core's correctness argument rests on a single database transaction that takes a
row lock, writes the hold, and appends to an outbox. Splitting the core from the catalogue or
the sweeper would put that transaction across a network boundary.

## Decision

One deployable Spring Boot application, internally divided into packages that are treated as
modules with explicit boundaries: `catalog`, `reservation`, `waitingroom`, `realtime`,
`allocation`, `payment`, `platform`. Modules talk through published interfaces and domain
events, never through each other's repositories. The application is stateless and scaled by
running N identical replicas.

## Alternatives considered

- **Separate reservation and real-time services.** Rejected for this scale: the fan-out service
  would need the same seat state and would learn about changes through the same pub/sub bus
  that the monolith already uses between replicas. The split adds a deployment unit and a
  failure mode and removes nothing.
- **Extract the sweeper as a job.** Rejected: the sweeper is a `SELECT ... FOR UPDATE SKIP
  LOCKED` loop that is safe to run on every replica concurrently. Making it a singleton job
  would add a leader-election dependency in exchange for no correctness improvement.

## Consequences

- Scaling is uniform: a replica that is mostly holding SSE connections still carries the
  reservation code. That is acceptable at this size and is measured in the capacity model.
- Module boundaries are a convention, not a compiler-enforced rule, so they will erode without
  attention. An architecture test asserts the allowed dependency directions.
- If the SSE connection count ever becomes the binding constraint before the database does, the
  fan-out module is the piece that is already shaped to be extracted, because it depends only
  on the delta contract.

## Falsifier

`ModuleBoundaryArchitectureTest` walks the compiled classes and fails if a module imports
another module's internal package. If the "modular" half of "modular monolith" stops being
true, the build fails.
