# Run context: 5000 SSE connections

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Started (UTC) | 2026-09-20 20:38:20Z |
| Target | `http://127.0.0.1:8080` |
| Connections requested | 5000 |
| Ramp | 45000 ms |
| Hold | 90 s |
| Git commit | `c79e700de7a57ce76b2f28a8ca12558ea54046ed` |
| Application replicas | 3 |
| Host kernel | Linux 6.6.114.1-microsoft-standard-WSL2 |
| Host logical cores | 32 |
| Host memory | 31Gi |
| Generator | Node v20.20.2, same host as the service |
| `ulimit -n` | 1048576 |
| `ip_local_port_range` | 32768 60999 |
| `somaxconn` | 4096 |
| `tcp_max_syn_backlog` | 2048 |
| Coalescing window | 50 ms, included in every propagation figure below |

## Caveats

- Propagation is measured from the PostgreSQL commit to the frame arriving at the client, and **includes** the 50 ms coalescing window and the outbox relay tick. Both are time a buyer waits; excluding them would make the number smaller and less true.
- Generator and service share a host, so their clocks are the same clock and the subtraction is exact. Across machines this measurement would need NTP-quality time or a round-trip estimate instead.
- Memory per connection is an upper bound: it attributes all growth during the window to the connections, including JIT compilation and heap growth caused by the seat-change traffic.
- The generator competes with the service for CPU on the same host.
