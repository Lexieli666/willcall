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

**And the falsifier itself needs a test that makes it fail.** A check that has never
been seen failing may not be capable of it. Four instruments in this project have
reported a pass while measuring nothing — the invariant script twice, a fault
injection that injected nothing, and a plan check that flagged three scans that were
not there. Every one would have been caught by asking it to fail once, and none was
caught by reading it carefully. So `verify-invariants.sh` is run against a planted
violation and against an unreachable database and must exit 1 and 2; the invariant
monitor has an integration test that writes a seat into two states at once past every
service; the game-day fixes were verified by re-running the scenario that found the
problem until it stopped reproducing.

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

## What these rules caught

The rules above are not aspirational. Each one has failed something real during this build, and
listing the failures is more use than restating the rules.

**No unmeasured numbers.** Three published figures were wrong because the *measurement* was wrong,
not the code:

- Memory per SSE connection, first reported at 212 KiB by sampling resident set before and during
  a run — which attributed the garbage from fifteen million delivered messages to the connections.
  Measured properly, the same code retained 90.5 KiB.
- The seat map's render time was over budget at 126 ms, and the cause was `Intl.NumberFormat` being
  constructed once per seat inside an accessible name. Nothing in the code looked expensive.
- The flash sale's sell-out watcher polled the full seat map four times a second, serialising five
  thousand rows into the middle of the burst it was observing — and the run it was measuring
  stopped selling out.

The rule that saved each of these is the same one: the raw file has to exist, and somebody has to
be able to re-derive the number from it. `scripts/build-results-summary.sh` now generates both the
summary and the README's headline table from those files, so a published figure cannot drift from
its source.

**The commit history is an artifact.** Two invariant-checker bugs were found by writing the commit
message. Explaining *why* a change was safe is where "wait, does that actually run?" tends to
surface.

**Every claim has a falsifier.** The checker that verifies the central invariant passed silently
twice — once because a shell function shadowed the `psql` it was looking for, once because
`docker run` without `-i` never received the query. Both times it reported seven checks passing
over nothing. The fix was structural rather than another careful line: the query now returns a
sentinel row, and the script fails if the sentinel does not come back. A checker that cannot tell
"no violations" from "no data" is worse than no checker, because it is believed.

The same failure mode turned up three more times, each in a different instrument:

- A game-day scenario that injected **nothing**. It opened 130 idle sessions on PostgreSQL on the
  reasoning that they would starve the application's pool. They do not — HikariCP's pool is client
  side — so the scenario would have reported the system surviving a fault it never experienced.
- A hot-path check that reported **three sequential scans that were not there**. Every plan
  contained a `Seq Scan on events`, and every one of them belonged to a subquery the plan-capture
  script had written and the application never runs. A check that flags correct code is a check
  people learn to ignore.
- A plan for the expiry sweeper taken against an **empty index**. After a load run there are no
  active holds, so the plan showed an index scan returning nothing in 0.1 ms. Building the
  population that matters — fifty thousand live holds of which fifty had expired — showed the same
  query reading 50,060 buffers to find 50 rows.

The pattern in all four is the same: the instrument returned a pass, and the pass meant nothing.
The lesson that generalises is not "be careful", it is that **every check needs a test that makes
it fail**. The invariant script is now run against a planted violation and against an unreachable
database, and is required to exit 1 and 2 respectively.

## Measuring before claiming

If a change is meant to make something faster, smaller or more reliable:

1. Measure it first and commit the raw file.
2. Make the change.
3. Measure again and commit that file too.
4. Put both numbers in the commit message.

"Reduced the relay tick to 25 ms" is a description. "Reduced the relay tick from 100 ms to 25 ms,
taking propagation p99 from 305 ms to 252 ms" is a result, and the next person can check it.

## Where the untrustworthy results went

A set of flash-sale results was deleted from this repository rather than kept, because they were
validated by the invariant checker during the window when it could not fail. Keeping raw files
whose central claim is unfounded would defeat the purpose of committing raw files at all. The
deletion is in the history with its reason.
