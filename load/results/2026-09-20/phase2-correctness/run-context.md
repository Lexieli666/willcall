# Run context: phase2-correctness

**local Docker Compose and Testcontainers, not AWS**

| Field | Value |
|---|---|
| Suite | backend `./gradlew test integrationTest` |
| Mode | ci |
| Recorded (UTC) | 2026-09-20 20:10:27Z |
| Git commit | `54285a944b388094646ab619671e8fa1dfa79b3d` |
| Host kernel | Linux 6.6.114.1-microsoft-standard-WSL2 |
| Host logical cores | 32 |
| Host memory | 31Gi |
| Java bytecode target | Java 21 (class file major 65) (from the compiled classes, not from whatever java is on PATH) |
| PostgreSQL | postgres:16-alpine via Testcontainers, fsync off |
| Redis | redis:7-alpine via Testcontainers |

## Caveats

- The concurrency figures are measured at the service layer against a real PostgreSQL, not over HTTP. They are a correctness result, not a latency one; HTTP percentiles come from the k6 scenarios.
- `fsync` and `synchronous_commit` are off in the test container. That makes the runs faster; it cannot make an oversell disappear, because an oversell would be visible in the same transaction that created it.
- Coverage is taken from the most recent `jacocoTestReport`, which runs in CI mode. Raising the repetition counts changes how many times a line executes, not which lines execute, so the percentage is the same in both modes.
- Every number in `test-results.json` was parsed from Gradle and JaCoCo output by `scripts/record-test-results.sh`. None was typed by hand.
