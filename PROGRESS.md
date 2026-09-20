# Willcall — build progress

Working log for the build. Phase, what is done, what failed, what is next. Updated as work
happens, not afterwards.

**Last updated:** 2026-09-20

---

## Environment

Recorded at the start of the run, on the machine that produced every measurement in this
repository.

| Tool | Version | Notes |
|---|---|---|
| OS | Linux 6.6.114.1-microsoft-standard-WSL2 | Ubuntu userland under WSL2 |
| Host CPU | 32 logical cores | shared between the service and the load generator |
| Host RAM | 31 GiB | |
| Java | OpenJDK 21.0.12 (`/usr/lib/jvm/java-21-openjdk-amd64`) | system default is 25; the build pins 21 via a Gradle toolchain |
| Gradle | 8.14.3 | installed to `~/.local/opt`, wrapper committed |
| Node | 20.20.2 | installed to `~/.local/opt/node20`, no sudo |
| npm | 10.8.2 | |
| Docker | 29.1.3 | |
| Docker Compose | v2.32.4 | |
| k6 | v2.2.0 | |
| Terraform | 1.16.3 | installed to `~/.local/bin`, no sudo |
| AWS CLI | 2.36.49 | installed to `~/.local/opt/aws-cli`, no sudo |
| GitHub CLI | 2.46.0 | authenticated as `Lexieli666` |
| Playwright browsers | see Phase 0 log | installed per project by npm |

### Working directory

The repository is checked out on a Windows drive mounted into WSL
(`/mnt/d/.../Projects Impl/willcall`). Measured there, creating 300 small files takes 472 ms
against 9 ms on the WSL ext4 filesystem — roughly 50× slower, which a Gradle build and an
`npm ci` would pay on every file. The build therefore runs in `~/willcall` on ext4 and is
mirrored to the Windows path, `.git` included, at each phase boundary.

Consequences, all deliberate:

- Gradle and npm caches stay in WSL (`~/.gradle`, `~/.npm`), which is the default.
- PostgreSQL and Redis data live in Docker **named volumes**, never bind mounts.
- `core.filemode=false` and `core.autocrlf=false` are set on the repository; file mode bits
  are not meaningful through the mount and are not relied on.

### Things that needed sudo and were worked around

`apt-get install` needs a password that is not available in an unattended run. Node 20,
Terraform, the AWS CLI and Gradle were installed as user-local tarballs under `~/.local`
instead. Nothing was skipped for want of sudo.

---

## AWS status: no credentials on this host

`aws sts get-caller-identity` fails with `NoCredentials`. Per the plan for this build, the
fallback path is in force and is recorded in
[ADR 0004](docs/adr/0004-run-on-local-docker-compose-until-aws-credentials-exist.md):

- The Terraform is written in full and held to `terraform fmt -check` and `terraform validate`.
  `terraform plan` **cannot** run without credentials and was not run.
- Everything else runs on local Docker Compose with three application replicas behind an nginx
  edge proxy.
- **Every load result is labelled "local Docker Compose, not AWS."**

### Pending because they need AWS or a human

- [ ] Deploy the stack to AWS and record the public URL.
- [ ] Game day against the deployed AWS service (ALB behaviour, RDS failover, cross-AZ).
- [ ] Public demo drop with ≥ 30 real users, with the traffic graph committed.
- [ ] Manual NVDA pass on Windows, with a screen recording.
- [ ] Manual VoiceOver pass on macOS, with a screen recording.
- [ ] The hold-timeout decision that follows from watching real users, written up as an ADR.

### Tear-down command (for when the AWS stack is eventually raised)

```bash
cd infra/terraform && terraform destroy -var-file=env/dev.tfvars -auto-approve
```

Nothing is deployed right now, so there is nothing to destroy.

---

## Phase log

### Phase 0 — skeleton, CI, a reachable page

**Status:** complete, except the public URL

- [x] Repository scaffolding: `server/ web/ load/ infra/ docs/ docs/adr/ scripts/ .github/workflows/`
- [x] Backend: Gradle 8.14.3, Java 21 toolchain, Spring Boot 3.5.16, Flyway, JDBC, Lettuce,
      Testcontainers, JUnit 5, jqwik, Spotless, Error Prone, JSON logs, `/health`, `/ready`,
      Micrometer Prometheus endpoint, OpenTelemetry OTLP export
- [x] Frontend: Vite 7, React 19, TypeScript 5 strict, ESLint (with `jsx-a11y` strict), Vitest,
      Playwright with `@axe-core/playwright`, Lighthouse CI budgets, responsive layout shell
- [x] Colour tokens with a unit test that fails the build if any text pair drops below 4.5:1 —
      it caught two failing pairs on first run and both were fixed
- [x] Dockerfiles; `docker compose --profile replicas up` brings up PostgreSQL, Redis, three
      application replicas and the nginx edge proxy, all reporting healthy
- [x] Terraform written for ALB + ECS Fargate + RDS PostgreSQL 16 + ElastiCache Redis 7;
      `terraform fmt -check` and `terraform validate` pass. `terraform plan` needs credentials
      and was not run.
- [x] CI workflow written: seven jobs covering static checks, unit and property tests,
      Testcontainers, end-to-end with axe and keyboard passes, Lighthouse budgets, the
      invariant check, a k6 smoke run, Terraform validation and repository hygiene
- [ ] Public URL — **blocked, no AWS credentials**. `http://127.0.0.1:8080` stands in.

#### Phase 0 VERIFY results

| Check | Command | Result |
|---|---|---|
| Backend static checks | `./gradlew spotlessCheck compileJava` | pass |
| Backend unit tests | `./gradlew test` | pass, 1 test |
| Frontend typecheck | `npx tsc -b` | pass |
| Frontend unit tests | `npm run test` | pass, 38 tests |
| Frontend lint | `npm run lint` | pass, 0 warnings |
| Compose config | `docker compose config -q` | pass |
| Compose up with health checks | `docker compose --profile replicas up -d` | all six containers healthy |
| Load spread across replicas | 60 concurrent requests through the edge | app1 20, app2 19, app3 21 |
| Terraform | `terraform fmt -check && terraform validate` | pass |
| Playwright smoke + axe + keyboard | `npx playwright test` | 3 passed, 0 axe violations |
| Lighthouse (3 runs, desktop) | `scripts/lighthouse.sh` | accessibility 100, performance 100, LCP 402-404 ms, CLS 0, TBT 0 |
| k6 smoke, 10 VUs, 60 s | `./scripts/run-load.sh smoke` | 1,200 requests, 0 failures, p99 8.34 ms |
| Repository hygiene | `scripts/check-no-secrets.sh` | pass |

Raw k6 output: `load/results/2026-09-20/smoke-*/`.

#### Things that went wrong in Phase 0, and what they cost

1. **Lettuce pooling failed at start-up** with `NoClassDefFoundError:
   GenericObjectPoolConfig`. Spring's pooled Redis factory needs `commons-pool2` on the
   classpath and the starter does not bring it. Every replica crash-looped until it was added.
2. **The edge proxy answered 400 on every `/api/` request.** nginx inherits
   `proxy_set_header` from an enclosing block only when the inner block declares none of its
   own, so one `proxy_set_header Connection ""` inside the location silently dropped `Host`;
   nginx then sent the upstream name, which contained an underscore, and Tomcat rejected it as
   an illegal domain name. Fixed by moving the headers into a shared include and renaming the
   upstream.
3. **Lighthouse created three 4 MB Chrome profile directories inside `web/`**, named with a
   literal Windows path, because `LOCALAPPDATA` is inherited from the Windows environment
   under WSL. They were caught by the hygiene check before the first push, and
   `scripts/lighthouse.sh` now points Chrome at a real temporary directory.

---

### Phase 1 — reservation core

**Status:** complete

- [x] Migrations for venues, events, sections, rows, seats (with version), holds, hold groups,
      orders, order lines, idempotency records and an outbox
- [x] APIs: create event, seat map, hold exact seats, hold best available, hold N together,
      confirm, cancel, buyer state, admin invariant check
- [x] Atomic allocation: `SELECT ... FOR UPDATE` on the seat rows, ordered by PostgreSQL, with no
      unprotected read-then-write anywhere in the core
- [x] Partial unique index `holds(seat_id) where status = 'ACTIVE'` as the database-level
      guarantee of single allocation
- [x] Idempotency with request fingerprinting; 422 on a mismatch, 409 + `Retry-After` on a
      concurrent duplicate, release-on-failure so a charge behind a timeout can be completed
- [x] Expiry via a `SKIP LOCKED` sweeper on every replica, no leader election
- [x] Confirm-vs-expire and cancel-vs-confirm resolved by one lock order
      ([ADR 0005](docs/adr/0005-one-lock-order-holds-then-seats.md))
- [x] Fake payment gateway with succeed / decline / timeout / succeed-after-timeout
- [x] `verify-invariants` as a script, a CI job and an in-process admin endpoint

#### Phase 1 VERIFY results

Raw file: `load/results/2026-09-20/phase1-correctness/` (`test-results.json` plus `run-context.md`).
Every figure below was parsed from Gradle and JaCoCo output by `scripts/record-test-results.sh`.

| Check | Result |
|---|---|
| `./gradlew test` | 14 tests, 0 failed |
| `./gradlew integrationTest` | 55 tests, 0 failed |
| Backend total | **69 tests, 0 failed** |
| Concurrency: 10,000 simultaneous holds at 500 seats | **50/50 runs**: exactly 500 granted, exactly 9,500 clean 409s, **0 unexpected failures, 0 oversells** every run |
| Concurrency wall clock per run | 555 ms min, 593 ms median, 957 ms max |
| Model-based property test | 10,000 random command sequences in long mode (1,000 in CI), service versus an independent reference state machine, 0 disagreements |
| Duplicate idempotency key replayed 100 times | 1 hold, 1 order; 100 simultaneous replays also produce 1 |
| Hold expiry | releases capacity exactly once; a second sweep claims nothing |
| Chaos: 30% duplicated requests, random declines and timeouts, abandoned checkouts, sweeper running throughout | invariants hold; no seat on two order lines |
| `scripts/verify-invariants.sh` against the live stack | 7/7 checks pass |
| Backend line coverage | **82.47%** (1,195 / 1,449 lines), floor 80% |

Method coverage 84.31%, class coverage 95.18%, branch coverage 59.59%. Branch coverage is the
weak one and is stated rather than omitted: the untested branches are mostly defensive
`IllegalStateException` paths that only fire if a lock was skipped.

#### Things that went wrong in Phase 1, and what they cost

Each of these was found by a test, not by reading the code. They are listed in
`docs/testing.md` with the test that found them.

1. **A decline released the seats and then un-released them.** Settlement signalled the decline by
   throwing out of a `@Transactional` method, which rolled back the release it had just performed.
2. **Checkout ran with no transaction at all.** `checkout()` called `prepareCheckout()` on `this`;
   Spring's `@Transactional` works through a proxy, so the `SELECT ... FOR UPDATE` ran in
   autocommit and dropped its locks immediately. It surfaced only because the outbox write demands
   an ambient transaction.
3. **A retry after a gateway timeout charged twice**, because each attempt minted a new order id
   and so defeated the gateway's own idempotency.
4. **Java and PostgreSQL disagreed about UUID order.** `UUID.compareTo` reads the most significant
   bits as a signed long; PostgreSQL compares sixteen unsigned bytes. Found by the model-based
   test on its first run.
5. **The invariant script passed when it could not reach the database.** It defined a shell
   function named `psql` and then asked `command -v psql` whether a client existed.
6. **Every unknown URL returned 500** with an error-level stack trace.

---

### Phase 2 — allocation, seat map, delta protocol

**Status:** complete

- [x] `docs/realtime-protocol.md` written **before** the implementation
- [x] Per-row segment tree (`maxFreeRun` / `prefixFreeRun` / `suffixFreeRun`), O(log n) query and
      update, with a leftmost-run search
- [x] JMH benchmark against a linear scan at 200 / 2,000 / 20,000 seats per row and 10 / 50 / 90%
      occupancy; crossover published in `load/results/2026-09-20/jmh-contiguous-search/`
- [x] jqwik property tests comparing the tree against brute force over 10,000 random patterns
- [x] `ContiguousSeatIndex`: the tree used as an in-memory hint the database then verifies
- [x] SSE hub with per-event coalescing, per-connection queues and resync-on-overflow
- [x] Redis pub/sub fan-out across replicas, with local delivery that does not depend on Redis
- [x] React seat map as real DOM, hold timer, checkout, confirmation, organizer dashboard,
      text-only list mode
- [x] End-to-end gap injection: a sequence number is genuinely burned on the server and the
      client is observed detecting and recovering

#### Phase 2 VERIFY results

Raw files: `load/results/2026-09-20/jmh-contiguous-search/`,
`load/results/2026-09-20/phase2-correctness/`, `load/results/2026-09-20/phase2-frontend/`.

| Check | Result |
|---|---|
| JMH: segment tree vs linear scan | 18 parameter combinations committed; crossover published |
| Property test: tree matches brute force | 10,000 random patterns per property, 0 disagreements |
| Backend tests | **110 total, 0 failed** (46 fast, 64 Testcontainers) |
| Backend line coverage | **86.47%** (1,796 / 2,077 lines), floor 80% |
| Frontend unit tests | **85 passed** |
| End-to-end tests | **15 passed**, including gap injection and the keyboard pass |
| Gap injection → resync | server burns a sequence number; the client detects it and recovers |
| 5,000-seat map render | **42.0 ms** against a 120 ms budget (100 rows × 50 seats) |
| Lighthouse, 3 runs, desktop | accessibility **100**, performance **100**, best practices **100** |
| Core Web Vitals | LCP 445 ms, CLS **0.0085**, TBT 0 ms |
| Gzipped JS for the route | 100.1 KB against a 180 KB budget |

**The crossover is not where it was expected.** The plan predicted "linear wins below ~500 seats
per row, tree wins at 2,000+". The measurement says the crossover is an *occupancy* level, not a
size: at 10% occupancy the linear scan wins at every size including 20,000 seats per row, and at
90% occupancy the tree wins everywhere. The tree's best case is answering "no" — where no run of
four exists it returns in 0.6 ns against 1,214 ns for the scan at 20,000 seats. Full analysis in
`load/results/2026-09-20/jmh-contiguous-search/crossover.md`.

#### Things that went wrong in Phase 2

1. **Coalescing broke gap detection.** A window batches three seat changes into one frame, but
   those changes consumed three sequence numbers. The frame carried only the highest, so every
   multi-seat hold looked to the client like two lost messages. Caught by the end-to-end test,
   which noticed the client reporting gaps that had not happened. Frames now carry the range.
2. **The seat map was 126 ms against a 120 ms budget**, and nothing in the code looked expensive.
   `formatMoney` was constructing an `Intl.NumberFormat` per seat while composing accessible
   names — five thousand formatters per render. Caching them took it to 42 ms.
3. **The ARIA grid structure was invalid.** `aria-pressed` is not supported on `role="gridcell"`,
   and a grid's children must be rows or rowgroups, so `grid > section > h3 > row` failed
   `aria-required-children`, `aria-required-parent` and `heading-order` at once. Found by ESLint
   and axe, not by review.
4. **Cumulative layout shift was 0.88** against a 0.05 budget: the events list replaced a
   one-line "Loading…" with a list of cards. Skeletons that reuse the real card markup took it to
   0.0085.
5. **A closed browser tab produced an ERROR with a stack trace.** `AsyncRequestNotUsableException`
   is a checked `IOException` subclass, so `catch (RuntimeException)` missed it, and Spring then
   failed a second time trying to render problem+json into a `text/event-stream` response. At five
   thousand connections that is five thousand stack traces for an ordinary disconnect.
6. **A stale buyer-state response re-adopted an expired hold**, so the page announced "your hold
   expired" and kept offering Pay for seats already back on sale.
7. **The Redis outage test could not see Redis come back.** Testcontainers maps an ephemeral host
   port and Docker assigns a new one on restart, so the application kept dialling the old address.

---

### Phase 3 — waiting room and real-time fan-out at load

**Status:** complete

- [x] Waiting room: Redis sorted set by arrival, atomic Lua token-bucket admission with no leader,
      signed admission tokens, live position over the same SSE stream
- [x] Admission rate tied to measured capacity and stored per event, not configured
- [x] 5,000 concurrent SSE clients against 3 replicas, with propagation measured from the
      PostgreSQL commit rather than from the fan-out
- [x] Per-connection memory measured by retained heap after a forced collection, not by a
      resident-set delta
- [x] Forced gap injection: a sequence number is burned on the server and the client is observed
      resyncing, in an end-to-end test
- [x] Redis restarted under load without violating the invariant
- [x] `docs/load-testing.md` records the Linux tuning the generator needs

#### Phase 3 VERIFY results

Run of record: `load/results/2026-09-20/sse-5000-205019/`. The generated table is in
[`docs/capacity-model.md`](docs/capacity-model.md); the figures are repeated nowhere by hand.

| Check | Result |
|---|---|
| Concurrent SSE connections | **5,000** established, 0 failed, spread 1,666 / 1,667 / 1,667 |
| Propagation, commit → client | p50 **113 ms**, p99 **223 ms** (target 80–250 ms) |
| Server-side flush | p99 **2.1 ms** — the rest of the latency is the relay tick, the coalescing window and the client |
| Sequence gaps seen by clients | **0** over 15,200,000 delivered changes |
| Retained heap per connection | **75.7 KiB** against a 10–60 KB target — **missed**, and why is in the capacity model |
| Redis restart under load | invariants held; the fan-out resumed without a resync storm |

#### Things that went wrong in Phase 3

1. **Per-connection memory was measured wrongly the first time.** Sampling resident set before and
    during the run attributed the garbage from delivering fifteen million messages to the
    connections and reported 212 KiB. A forced collection with the connections open, compared
    against the same figure after closing them, gave 90.5 KiB for identical code. Tomcat's 8 KiB
    per-connection buffers then took it to roughly 74–76 KiB.
2. **The first run's propagation p99 was 305 ms.** The outbox relay ticked every 100 ms, so a
    commit waited up to a full tick before anyone heard about it. A 25 ms tick cost more database
    round trips and was worth it.
3. **A closed tab produced a stack trace per disconnect** — see Phase 2, item 5; it only became
    visible at five thousand connections.

---

### Phase 4 — flash sale, fairness, capacity model

**Status:** complete apart from the capacity sweep re-run noted below

- [x] Flash sale: 10,000 buyers arriving inside 10 s for 5,000 seats, **50 runs**
- [x] Invariants verified after every single run, not once at the end
- [x] Rate limiting: a single client flooding the API is shed with 429 and `Retry-After`
- [x] Waiting-room fairness measured in SQL from the `admissions` table
- [x] `docs/capacity-model.md` with the measured figures, the named bottleneck and an explicitly
      labelled one-million-user extrapolation
- [x] Seeded dataset of 1,000,000 users, 50,000 events and 1,000,000 historical orders, with
      `EXPLAIN ANALYZE` plans committed
- [x] Capacity sweep: the sustainable hold rate measured across a range of offered rates

#### Phase 4 VERIFY results

Raw files: `load/results/2026-09-20/flash-suite-211316/`, `fairness-*`, `ratelimit-*`,
`capacity-sweep-*`, `query-plans/`. The full generated table is
[`load/RESULTS_SUMMARY.md`](load/RESULTS_SUMMARY.md).

| Check | Result |
|---|---|
| Flash sale, 50 runs | **50/50 invariants held, 0 oversells, 0 server errors** |
| Time to sell out | 7.4 s min, **8.0 s median**, 10.5 s max (expected 8–45 s; faster than the range) |
| Hold p99 during the unpaced burst | 2,312 / **2,447** / 3,205 ms across runs |
| Requests shed as 503 during the burst | 1,205 / 1,664 / 3,680 per run |
| Rate limiting | every shed request carried `Retry-After`; no request was shed without one |
| FIFO inversion rate | see `load/RESULTS_SUMMARY.md` — published with the raw pair counts beside it |

#### Things that went wrong in Phase 4

1. **The invariant checker passed silently, twice.** A shell function named `psql` was found by
   `command -v`, and later `docker run` without `-i` meant the heredoc never reached psql — so the
   script exited 0 having checked nothing. Every flash-sale result it had signed off was deleted
   from the repository rather than relabelled. The script now resolves the binary with `type -P`,
   probes connectivity, checks the tables exist, and emits a sentinel row that must come back or
   it exits 2. Both directions are tested: a planted violation exits 1, an unreachable database
   exits 2.
2. **1,927 responses were 5xx in the first flash sale.** `errorForEmptyAllocation` ran a
   `count(*)` on every refusal — thirteen thousand of them per run — and exhausted the connection
   pool. Removing the query was the fix; mapping pool exhaustion to 503 with `Retry-After` was the
   second fix, because a saturated service should say so rather than return 500.
3. **Nine runs in ten skipped their checkout.** The load script gated it on `__ITER % 10`, and
   under `ramping-arrival-rate` most virtual users run exactly one iteration, so the condition was
   almost never true. `__VU % 10` measures what was intended.
4. **The sell-out watcher perturbed the thing it measured**, polling the whole 5,000-seat map four
   times a second. A dedicated availability endpoint replaced it.
5. **Results drifted as the database grew** — sell-out went from 8 s to 31 s over eight runs on a
   catalogue nothing truncated. The suite now resets between runs, and database growth is measured
   where it belongs: in the seeded million-row dataset and its query plans.
6. **The first paced run asserted a rate the stack cannot serve.** At 1,000 requests/s it shed
   65.7% of traffic as 503 and returned a hold p99 of 4.6 s. That is a fact about the hardware, not
   a measurement of capacity, so the single point was replaced by a sweep that finds the ceiling.
7. **The aggregators counted `run-context.md` as a run**, because they globbed `run-*` in a
   directory that also holds a file starting with `run-`.

---

### Phase 5 — accessibility and front-end performance

**Status:** complete apart from the two manual screen-reader passes, which need a human

- [x] axe clean on every route and state, enforced in CI
- [x] Lighthouse accessibility 100 and performance ≥ 90 desktop, enforced as a budget
- [x] A Playwright test completes a purchase using only keyboard events, including
      two-dimensional arrow navigation of the seat grid
- [x] Text-only list mode
- [x] Reduced motion honoured; every text pair at 4.5:1 or better, enforced by a unit test
- [x] Seat state never conveyed by colour alone — a glyph carries it, and it is in the accessible
      name
- [ ] **Manual NVDA pass on Windows with a screen recording — needs a human**
- [ ] **Manual VoiceOver pass on macOS with a screen recording — needs a human**

#### Phase 5 VERIFY results

| Check | Result |
|---|---|
| axe violations | **0** across the routes and states the end-to-end suite visits |
| Lighthouse accessibility | **100** |
| Lighthouse performance, desktop | **100** |
| Largest Contentful Paint | **445 ms** (budget 1,500 ms) |
| Cumulative Layout Shift | **0.0085** (budget 0.05) |
| Keyboard-only purchase | passes, with no `click()` anywhere in the specification |
| 5,000-seat map render | **42 ms** (budget 120 ms) |
| Gzipped JavaScript for the route | **100.3 KB** (budget 180 KB) |

---

### Phase 6 — observability, game day, publication

**Status:** in progress

- [x] Prometheus RED metrics, OpenTelemetry traces, a committed Grafana dashboard
- [x] `docs/slo.md` with the objectives, the error budgets and what spending one means
- [x] Game day against the running service: kill a replica, exhaust the PostgreSQL pool, restart
      Redis, add latency — each with the hypothesis written before the run
- [x] `docs/incidents/<date>-<name>.md` per scenario, with timeline, detection, root cause and fix
- [x] README with every number traceable to a raw file under `load/results/`
- [ ] Grafana dashboard screenshots — the dashboard is committed; the images are not yet captured
- [ ] **Demo drop with ≥ 30 real humans and the traffic graph committed — needs a human**
- [ ] **The hold-timeout decision that follows from watching those users — needs a human**

---

## What is next

The measured work is finished apart from the items above that need a human or AWS credentials.
Those are listed in full under "Pending because they need AWS or a human" near the top of this
file, and they are not claimed anywhere in the README or the results summary.
