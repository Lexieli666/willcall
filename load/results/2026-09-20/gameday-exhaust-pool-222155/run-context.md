# Run context: game day, exhaust-pool

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Scenario | `exhaust-pool` |
| Started (UTC) | 2026-09-20 22:23:57Z |
| Fault injected at | 2026-09-20T22:22:36Z |
| Load | holds scenario at 150/s for 120s |
| Git commit | `a47d66849cd907cfb3f105f5617695d2d943dff1` |
| Host logical cores | 32 |

## Caveats

- Local Compose cannot exercise Application Load Balancer behaviour, cross-availability-zone failure, or RDS failover. Those remain outstanding and are listed in PROGRESS.md.
- Metrics are sampled every two seconds from each replica directly. A counter that resets because a replica restarted shows as a negative delta; the kill-replica scenario is read with that in mind.
