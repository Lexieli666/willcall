# Run context: smoke

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Scenario | `load/scripts/smoke.ts` |
| Started (UTC) | 2026-09-20 18:36:20Z |
| Target base URL | `http://127.0.0.1:8080` |
| Git commit | `7bd374a42ee4f16fe74ae04e62746f7f63f7b327` |
| Application replicas | 3 |
| Host kernel | Linux 6.6.114.1-microsoft-standard-WSL2 |
| Host logical cores | 32 |
| Host memory | 31Gi |
| Load generator | k6 v2.2.0, same host as the service |
| `ulimit -n` | 1048576 |
| `ip_local_port_range` | 32768 60999 |
| `somaxconn` | 4096 |

## Container limits at run time

| Container | Image | CPU limit | Memory limit |
|---|---|---|---|
| `willcall-app1-1` | willcall/server:local | 2.00 vCPU | 2.00 GiB |
| `willcall-app2-1` | willcall/server:local | 2.00 vCPU | 2.00 GiB |
| `willcall-app3-1` | willcall/server:local | 2.00 vCPU | 2.00 GiB |
| `willcall-edge-1` | nginx:1.27-alpine | unlimited | unlimited |
| `willcall-postgres-1` | postgres:16-alpine | 4.00 vCPU | 8.00 GiB |
| `willcall-redis-1` | redis:7-alpine | 2.00 vCPU | 2.00 GiB |

## Caveats

- The load generator shares the host with the service, so generator CPU competes with application CPU. The host core count above is the total available to both.
- Network latency between generator and service is loopback, near zero. Percentiles here are therefore service time plus loopback, not service time plus internet.
- Numbers from this file may be published only with the deployment label at the top of this document attached.
