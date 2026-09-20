# Run context: flash-sale suite, 50 runs

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Started (UTC) | 2026-09-20 21:48:25Z |
| Runs | 50 |
| Buyers per run | 10000 arriving within 10s |
| Seats per run | 5000 |
| Git commit | `10bf6ed9d797e13a41fe7a714cdbd60606717384` |
| Application replicas | 3 |
| Host logical cores | 32 |
| k6 | v2.2.0, same host as the service |

## Caveats

- The catalogue is truncated before each run, so the fifty runs are comparable. Without it the database grows by five thousand seats a run and the sell-out time drifts with table size rather than with contention - measured at eleven seconds on run one and thirty-one by run eight. Growth under a long-lived catalogue is measured separately, by the seeded million-row dataset and its query plans.
- Runs contend for CPU with the generator, which shares the host.
- The invariant is checked against the database after every run, not inferred from response codes. A run whose invariant check fails is counted as an oversell and is kept, not retried.
- A 409 is a correct answer under a sell-out and is not counted as an error. Only 5xx and transport failures are.
