# Willcall

Willcall sells a fixed inventory of seats to a crowd that arrives all at once.

It admits buyers from a fair waiting room, lets them pick exact seats or ask for *N* seats
together, holds seats with an expiring timer, confirms checkout idempotently, pushes live seat
state to every open browser, and does all of it without overselling. The seat map is operable
with a keyboard alone or with a screen reader, and CI blocks any merge that breaks that.

> **Status:** under construction. Numbers appear in this file only once a raw result file
> under `load/results/` exists to back them. Progress, including what is blocked, is in
> [PROGRESS.md](PROGRESS.md).

## The invariant

For every event: `confirmed + unexpired holds <= capacity`, and no seat has more than one
active allocation.

PostgreSQL owns correctness; Redis only accelerates. If Redis disappears, reservations stay
correct and the queue degrades. See
[ADR 0001](docs/adr/0001-postgresql-owns-correctness-redis-only-accelerates.md).

## Architecture

```
  browser (React 19 + TypeScript)                k6 load generators
    REST/JSON  +  SSE (snapshot + deltas)        (fd limits, ephemeral ports tuned)
             |
   Java 21 / Spring Boot 3 modular monolith (N replicas behind a load balancer)
     event API | reservation core | waiting room | SSE hub | sweeper
             |                 |                   |
        PostgreSQL 16       Redis 7           Redis pub/sub
   source of truth:      waiting-room ZSET,   cross-replica seat-delta bus
   seats with version,   admission tokens,
   SELECT ... FOR UPDATE idempotency cache,
   SKIP LOCKED, outbox   rate limits
```

## Scope

Read [docs/product-scope.md](docs/product-scope.md) for the product argument. In short:

**In scope** — venues, events, sections, rows, seats, price tiers; the seat lifecycle
`available → held(expiry) → sold` plus `released` and `blocked`; exact-seat and
best-available-*N*-together acquisition; holds with a TTL and a sweeper; idempotent checkout; a
fake payment gateway that can fail, time out, or succeed *after* timing out; a fair waiting
room; live seat state over Server-Sent Events; a React front end including an organizer
dashboard; Prometheus metrics, OpenTelemetry traces, Grafana dashboards and SLOs; k6 load
scripts; a one-million-user seeded dataset for query-plan checks; Docker; Terraform; deploy on
merge.

**Not in scope** — real payments; seller onboarding; search and recommendations; email and SMS;
fraud or bot detection beyond rate limits; native mobile (responsive web only); Kafka;
Kubernetes; microservice decomposition. The backend is a modular monolith
([ADR 0003](docs/adr/0003-modular-monolith-not-microservices.md)).

## Running it

Requirements: Docker with Compose v2, JDK 21, Node 20.

```bash
# three replicas behind the local edge proxy — the configuration every load test targets
make up
open http://127.0.0.1:8080

# or a single replica plus the Vite dev server, for development
make dev
cd web && npm run dev
```

| Command | What it does |
|---|---|
| `make build` | Compile backend and frontend |
| `make test` | Backend unit and property tests, frontend unit tests |
| `make integration` | Testcontainers suite against real PostgreSQL and Redis |
| `make test-long` | The same suites at long-mode repetition counts |
| `make e2e` | Playwright, including the axe and keyboard-only passes |
| `make lighthouse` | Lighthouse CI budgets |
| `make lint` | Spotless, Error Prone, ESLint, `tsc` |
| `make verify-invariants` | Assert the invariant directly against the database |
| `make load-smoke` | Short k6 run against the local stack |
| `make bench` | JMH: segment tree against linear scan |
| `make down` / `make clean` | Stop the stack, optionally deleting volumes |

`make help` lists everything.

## Where the numbers come from

Every figure published here has a raw file under `load/results/<date>/` and a `run-context.md`
next to it naming the commit, the host, the container limits, the replica count, and whether
the run was local or cloud. `load/RESULTS_SUMMARY.md` maps each published number to its raw
source and states which targets were missed.

## Documentation

| Document | What it covers |
|---|---|
| [docs/product-scope.md](docs/product-scope.md) | The problem, the users, what a user sees when things go wrong |
| [docs/testing.md](docs/testing.md) | What is tested at which layer, and the falsifier for each claim |
| [docs/adr/](docs/adr/) | Decisions, including the product ones |
| [CONTRIBUTING.md](CONTRIBUTING.md) | The three rules this repository is built under |
| [PROGRESS.md](PROGRESS.md) | Current state, including what is blocked and why |

## Limitations

Stated up front rather than discovered by a reader:

- No AWS deployment yet — see [PROGRESS.md](PROGRESS.md) and
  [ADR 0004](docs/adr/0004-run-on-local-docker-compose-until-aws-credentials-exist.md).
  Results are measured on local Docker Compose and labelled as such.
- The manual screen-reader passes, the public demo, and the hold-timeout decision that follows
  from watching real users all need a human and are listed as pending, not claimed.

## Licence

MIT. See [LICENSE](LICENSE).
