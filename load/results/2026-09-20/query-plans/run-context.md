# Run context: query plans at scale

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Collected (UTC) | 2026-09-20 22:04:42Z |
| Git commit | `2696e27bc6e458ff81e2ccbe14b5f0b0d9821ca1` |
| Users seeded | 1000000 |
| Events seeded | 50000 |
| Historical orders seeded | 1000000 |
| PostgreSQL | postgres:16-alpine, 4 vCPU, 8 GiB, `shared_buffers=1GB` |

## Caveats

- `EXPLAIN ANALYZE` executes the query, so the timings include the run. They are indicative rather than a benchmark; the plan shape is the thing to read.
- The database was `ANALYZE`d immediately before collection, so the planner had current statistics. A plan taken with stale statistics is a plan for a database that does not exist.
- Buffer counts are included (`EXPLAIN (ANALYZE, BUFFERS)`) because "fast" on a warm cache and "fast" on a cold one are different claims.
