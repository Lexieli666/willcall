# Run context: game day, kill-replica

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Scenario | `kill-replica` |
| Started (UTC) | 2026-09-20 21:56:30Z |
| Fault injected at | 2026-09-20T21:55:09Z |
| Load | holds scenario at 150/s for 120s |
| Git commit | `0756d51fa028ae38945ed38d7e2e091593bcc991` |
| Host logical cores | 32 |

## Caveats

- Local Compose cannot exercise Application Load Balancer behaviour, cross-availability-zone failure, or RDS failover. Those remain outstanding and are listed in PROGRESS.md.
- Metrics are sampled every two seconds from each replica directly. A counter that resets because a replica restarted shows as a negative delta; the kill-replica scenario is read with that in mind.
