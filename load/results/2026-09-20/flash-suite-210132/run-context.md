# Run context: flash-sale suite, 1 runs

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Started (UTC) | 2026-09-20 21:02:05Z |
| Runs | 1 |
| Buyers per run | 10000 arriving within 10s |
| Seats per run | 5000 |
| Git commit | `b178904abf880478ef9ba55876df35ae3fd1471b` |
| Application replicas | 3 |
| Host logical cores | 32 |
| k6 | v2.2.0, same host as the service |

## Caveats

- Each run creates a fresh event, so runs do not contend with one another for seats. They do contend for CPU with the generator, which shares the host.
- The invariant is checked against the database after every run, not inferred from response codes. A run whose invariant check fails is counted as an oversell and is kept, not retried.
- A 409 is a correct answer under a sell-out and is not counted as an error. Only 5xx and transport failures are.
