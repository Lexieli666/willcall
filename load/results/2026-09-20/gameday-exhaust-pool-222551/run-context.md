# Run context: game day, exhaust-pool

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Scenario | `exhaust-pool` |
| Started (UTC) | 2026-09-20 22:27:53Z |
| Fault injected at | 2026-09-20T22:26:31Z |
| Load | holds scenario at 150/s for 120s |
| Git commit | `04d3067ec3fba49585e6c3f5f9cf9fa5b59f0219` |
| Host logical cores | 32 |

## Caveats

- Local Compose cannot exercise Application Load Balancer behaviour, cross-availability-zone failure, or RDS failover. Those remain outstanding and are listed in PROGRESS.md.
- Metrics are sampled every two seconds from each replica directly. A counter that resets because a replica restarted shows as a negative delta; the kill-replica scenario is read with that in mind.
