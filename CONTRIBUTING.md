# Contributing to Willcall

Willcall is a single-owner project, but the working rules below are what keep the
claims in `README.md` checkable by someone who has never seen the code. They apply
to every change, including one-line ones.

## The three rules

### 1. No unmeasured numbers

If a number appears in `README.md`, an ADR, a commit message, a release note, or a
resume bullet, a raw result file that produced it is committed under `load/results/`
(or, for benchmarks, `load/results/<date>/jmh-*.json`). "About 100 ms" with nothing
behind it is a bug, not a rounding.

Every raw result directory carries a `run-context.md` naming: the git commit, the
host, the CPU/RAM available to each component, the replica count, the database and
cache sizing, the load-generator host, and whether the run was against local Docker
Compose or a cloud deployment. A percentile without that context is not a result.

When a target from the specification is not met, the measured value is published
next to the target with the gap stated. Missing a target is a finding; hiding it is
a fabrication.

### 2. The commit history is an artifact

Commits are small, and the message says *why*, not what the diff already shows.
Subject line in the imperative, under 72 characters, then a body explaining the
reason for the change and, where relevant, what it would take to show the change is
wrong.

A reviewer should be able to read `git log` and reconstruct the decision sequence
without opening a single file.

### 3. Every claim has a falsifier

Any statement in the docs about behaviour names the test, job, or command that would
fail if the statement were untrue. "Zero oversells under a 10,000-client burst" is
paired with the concurrency test that fails the build if an oversell occurs.
"Accessible by keyboard" is paired with the Playwright test that drives the entire
purchase with key events only. If you cannot name the falsifier, the claim is an
intention and belongs in `PROGRESS.md`, not `README.md`.

## Workflow: tests and load scripts before implementation

For anything with a correctness or capacity claim attached:

1. Write the protocol or behaviour document first (`docs/realtime-protocol.md` was
   written before the SSE hub existed).
2. Write the failing test, or the k6 script that measures the thing, first.
3. Implement until it passes.
4. Commit the raw result alongside the code that produced it.

This ordering is why the invariant tests are trustworthy: they existed before the
code that had to satisfy them, so they were never shaped to fit an implementation.

## Decisions are ADRs

Anything that constrains later work — a product decision such as the hold TTL, or a
technical one such as choosing Server-Sent Events over WebSocket — is written as a
numbered ADR under `docs/adr/` using the template in `docs/adr/0000-template.md`.
An ADR states the context, the decision, the alternatives that were rejected and
why, and the consequences that were accepted.

## Before you push

```bash
make lint          # Spotless + Error Prone + ESLint + tsc --noEmit
make test          # backend unit + property tests, frontend unit tests
make integration   # Testcontainers: Postgres + Redis
make e2e           # Playwright, including the axe and keyboard-only passes
make verify-invariants
```

CI runs the same commands. A merge is blocked by any axe violation, by a Lighthouse
accessibility score below 100, by a performance budget regression, by a failing
invariant check, and by a backend coverage drop below the configured floor.

## Code style

- Java 21, formatted by Spotless (`make format`). Error Prone runs at build time.
- TypeScript 5 in `strict` mode. No `any` without a comment naming what is unknown.
- SQL migrations are append-only. Never edit a migration that has been applied.
