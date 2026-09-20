# Run context: capacity sweep

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Collected (UTC) | 2026-09-20 22:39:38Z |
| Git commit | `2e448a40d01cf37d4e6b1196a3a1b0333a39ad38` |
| Offered rates | 100 200 300 400 600 800 1000 requests/s |
| Duration per step | 45s |
| Application | 3 replicas, 2 vCPU / 2 GiB each |
| Load generator | k6 on the same host, competing for CPU |
