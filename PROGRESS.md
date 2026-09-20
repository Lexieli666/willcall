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

**Status:** in progress

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
- [ ] Phase 2 numbers folded into the README

---

## What is next

Finish Phase 2 verification, then Phase 3 (waiting room and real-time at load).
