# Changelog

## v1.0 — 2026-09-20

The first version whose every published number has a committed raw file behind it.

### What it does

Sells a fixed inventory of seats to a crowd that arrives at once, and does not sell one twice. Two
acquisition paths (exact seats, best available N together), holds with a TTL and a leaderless
sweeper, idempotent checkout against a fake gateway that can decline, time out, or succeed after
timing out, a fair waiting room, a real-time seat map over Server-Sent Events, and an accessible
front end. Three replicas behind a proxy, PostgreSQL for correctness, Redis for speed.

### What is measured

The full table with every target and every miss is
[`load/RESULTS_SUMMARY.md`](load/RESULTS_SUMMARY.md), generated from the raw files. The headlines:

- **50 flash-sale runs**, 10,000 buyers in 10 seconds for 5,000 seats: invariants intact after
  every one, zero oversells, zero server errors. The invariant is checked after each run, not once
  at the end.
- **5,000 concurrent SSE connections** across three replicas, zero failures, propagation from
  database commit to browser at 223 ms p99, zero sequence gaps over 15.2 million delivered changes.
- **A million-row dataset** with query plans committed, and no hot path sequentially scanning.
- **Lighthouse accessibility 100**, performance 100, and a Playwright test that completes a whole
  purchase with no mouse.

### What is missed, and stated as missed

- **The hold rate.** The plan assumed ~1,000 requests/s; the measured sustainable rate on this
  hardware is 200. The sweep that found it also found goodput falling above 600 offered — more load,
  less work done.
- **Retained heap per SSE connection** is 75.7 KiB against a 10–60 KB target. Closing it means not
  holding a thread per connection, which is a redesign rather than a tuning change.
- **Nothing is deployed.** No AWS credentials on the build host. The Terraform is written and
  validated; `terraform plan` has never run.
- **No human has used it.** The manual screen-reader passes and the public demo have not happened,
  so [ADR 0012](docs/adr/0012-hold-ttl.md) stays `proposed` and the hold TTL is an unvalidated
  default.

### What the game day found

Four faults injected while traffic flowed. The one worth reading is
[the connection-pool outage](docs/incidents/2026-09-20-connection-pool-exhaustion.md): the
application shed 4,427 requests correctly with `503` and `Retry-After` and returned no `500`s, and
the proxy then ejected all three healthy replicas and answered `502` to 1,227 buyers for nine
seconds. Nothing counted them, because the application never saw them. The fixes are in this
release and were verified by re-running the scenario until it logged no ejections.

### Notable in the code

- One lock order everywhere, and a comparator for it, because `UUID.compareTo` is signed and
  PostgreSQL's uuid ordering is not ([ADR 0005](docs/adr/0005-one-lock-order-holds-then-seats.md)).
- Idempotency that releases its key on failure, so a buyer whose charge succeeded behind a timeout
  is not stranded by a replayed 504 ([ADR 0006](docs/adr/0006-idempotency-releases-its-key-on-failure.md)).
- Delta frames carry a sequence *range*, because coalescing three changes into one frame consumed
  three numbers and every client reported a gap.
- `lock_timeout` on application connections, and a SQL exception translator, because a timeout
  without a decision about what its expiry means is half a change.
- Passive proxy health checks disabled deliberately
  ([ADR 0013](docs/adr/0013-liveness-is-the-health-checks-job-not-the-proxys.md)).

### Known-good commands

```bash
make up          # three replicas behind the edge proxy
make verify      # every check this repository claims, one pass/fail line each
make results     # regenerate every published number from the raw files
```
