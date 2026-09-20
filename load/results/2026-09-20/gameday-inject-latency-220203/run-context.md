# Run context: game day, inject-latency

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Scenario | `inject-latency` |
| Started (UTC) | 2026-09-20 22:04:05Z |
| Fault injected at | 2026-09-20T22:02:44Z |
| Load | holds scenario at 150/s for 120s |
| Git commit | `2696e27bc6e458ff81e2ccbe14b5f0b0d9821ca1` |
| Host logical cores | 32 |

## Caveats

- Local Compose cannot exercise Application Load Balancer behaviour, cross-availability-zone failure, or RDS failover. Those remain outstanding and are listed in PROGRESS.md.
- Metrics are sampled every two seconds from each replica directly. A counter that resets because a replica restarted shows as a negative delta; the kill-replica scenario is read with that in mind.
