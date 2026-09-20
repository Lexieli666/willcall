# Testing strategy

The point of this document is to say, for each claim Willcall makes, which test would fail if
the claim were false. A test that cannot fail for a stated reason is not evidence.

## Layers

| Layer | Runner | Where | What it is for |
|---|---|---|---|
| Unit | JUnit 5 | `server/src/test` | Pure logic: the segment tree, the token bucket, the state machine, request fingerprinting |
| Property | jqwik | `server/src/test` | Claims that must hold for *all* inputs, not for the examples somebody thought of |
| Model-based | jqwik | `server/src/test` | Random sequences of hold/retry/confirm/cancel/expire compared against a reference state machine |
| Integration | JUnit 5 + Testcontainers | `server/src/integrationTest` | Real PostgreSQL and real Redis: transactions, isolation, `SKIP LOCKED`, partial unique indexes |
| Concurrency | JUnit 5 + Testcontainers | `server/src/integrationTest` | 10,000 simultaneous hold attempts at 500 seats |
| Architecture | JUnit 5 | `server/src/test` | Module boundaries stay where ADR 0003 says they are |
| Frontend unit | Vitest | `web/src/**/*.test.tsx` | Component behaviour, colour-token contrast, protocol client logic |
| End-to-end | Playwright | `web/e2e` | The real purchase path in a real browser against a real backend |
| Accessibility | Playwright + axe-core | `web/e2e` | Zero violations on every route *and every state*, not just the empty page |
| Keyboard | Playwright | `web/e2e` | A complete purchase driven only by key events |
| Budget | Lighthouse CI | `web/lighthouserc.cjs` | Accessibility 100, desktop performance ≥ 0.90, LCP/CLS/TBT ceilings |
| Load | k6 | `load/scripts` | Capacity, percentiles, oversell count, propagation latency |
| Invariant | SQL | `scripts/verify-invariants.sh` | The central invariant, asserted against the database itself |

## The central invariant

For every event: `confirmed + unexpired holds <= capacity`, and no seat has more than one
active allocation.

It is defended at four independent levels, so that a bug has to defeat all four to produce an
oversell:

1. **Schema.** A partial unique index permits at most one `ACTIVE` hold per seat. A seat's
   status column is constrained to the legal set. Foreign keys make an order line without a
   seat impossible.
2. **Transaction.** Every acquisition locks its seat rows with `SELECT ... FOR UPDATE` in a
   deterministic order (by seat id, to avoid deadlock between two multi-seat requests) before
   any write. There is no read-then-write outside a lock anywhere in the reservation core.
3. **Tests.** The concurrency test fires 10,000 simultaneous attempts at 500 seats and asserts
   exactly 500 successes, 9,500 clean `409`s, zero `5xx`, and zero oversells.
4. **External check.** `scripts/verify-invariants.sh` queries the database directly, so it
   catches a violation the application cannot see. It runs in CI, after every load test, and at
   the end of the game day.

## Repetition counts

Concurrency and property tests are cheap to run a few times and expensive to run many times.
Both counts are controlled by one system property:

```bash
make test                 # CI mode:   concurrency 5 runs,  model test 1,000 sequences
make test-long            # long mode: concurrency 50 runs, model test 10,000 sequences
```

`-Dwillcall.longMode=true` switches the counts. CI runs the fast counts on every push and the
long counts nightly, because a 50-run concurrency suite is minutes of wall clock that would
otherwise be paid on every commit.

## Tests before implementation

For anything with a correctness or capacity claim, the order is: write the document, write the
failing test or the k6 script, then implement. `docs/realtime-protocol.md` was written before
the SSE hub existed; the concurrency test was written before the hold path could pass it. This
is what makes the tests independent evidence rather than a description of whatever the code
already does.

## What is deliberately not tested automatically

- **Screen-reader behaviour with NVDA and VoiceOver.** axe and Lighthouse catch the machine-
  checkable half. Whether an announcement is *useful* needs a person. The manual passes are
  tracked as a checklist in `docs/accessibility.md` and are explicitly not claimed until done.
- **Real-user behaviour under a real drop.** Synthetic load generators are patient and
  identical; people are neither.

## Bugs these tests found before a human did

Listed because a test suite's value is what it caught, not how many assertions it contains.

| Found by | The bug |
|---|---|
| `ReservationCoreIntegrationTest` (declined payment) | Settlement threw an `ApiException` out of a `@Transactional` method to signal a decline. The throw rolled the transaction back, undoing the seat release it had just performed. Two seats never came back on sale. Settlement now returns an outcome and the caller translates it after the transaction commits. |
| `ReservationCoreIntegrationTest` (outbox write) | `checkout()` called `prepareCheckout()` and `settleCheckout()` on `this`. Spring's `@Transactional` works through a proxy, so both ran with no transaction: the `SELECT ... FOR UPDATE` released its locks immediately. It surfaced only because the outbox write demands an ambient transaction. The orchestration moved to its own bean. |
| `ReservationCoreIntegrationTest` (succeed-after-timeout) | Each checkout attempt minted a new order id, which defeated the gateway's own idempotency and charged twice for the one case idempotency exists to cover. Checkout now reuses the pending order for the hold group. |
| `ReservationModelPropertyTest` | `UUID.compareTo` compares the most significant bits as a *signed* long; PostgreSQL compares uuids as sixteen unsigned bytes. The service picked the seat the database considered lowest, the model expected the one Java did. `SeatOrdering` is now the single comparator, and `SeatOrderingTest` pins it with the exact pair that exposes the difference. |
| `IdempotencyIntegrationTest` | Storing and replaying failures looked correct and stranded any buyer whose charge succeeded behind a gateway timeout: the retry replayed the stored 504 instead of reaching the gateway and finding the charge. Failures now release the key. |
| `ReservationApiIntegrationTest` | Any unknown URL returned 500 with an error-level stack trace, because the catch-all handler also caught `NoResourceFoundException`. During a load test that noise would have hidden a real 500. |
| Running `verify-invariants.sh` by hand | The script defined a shell function called `psql` and then asked `command -v psql` whether a client existed. `command -v` finds functions, so the answer was always yes, the binary was missing, every query failed, every check saw empty output, and the script printed "all invariant checks passed". It now resolves the client with `type -P`, probes connectivity, checks the tables exist, and fails if fewer than seven checks ran. |
| `docker compose` smoke check | An nginx prefix location ending in a slash makes nginx 301 a request for the same path without it — so every `POST /api/events` was redirected to `/api/events/`, and the redirect dropped the port. The stream location is now a regex. |
