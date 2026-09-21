# Run context: phase6-frontend

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Recorded (UTC) | 2026-09-21 03:08:16Z |
| Git commit | `fafc2bf768922d6fad8a0a068716d7dbc7fa37d1` |
| Target | http://127.0.0.1:8080/ |
| Host kernel | Linux 6.6.114.1-microsoft-standard-WSL2 |
| Host logical cores | 32 |
| Browser | Chromium via Playwright, headless |
| Lighthouse preset | desktop, 3 runs, minimum score reported |

## Caveats

- Lighthouse ran against the local edge proxy on loopback, so network time is near zero. LCP here is render time plus a negligible transfer, not what a phone on a mobile network would see.
- The seat-map render figure is the application's own `performance.measure`, from the moment the seat data is available to the frame after the DOM is committed. It excludes the fetch deliberately: it measures building and painting the map, which is what the budget is about.
- Vitest coverage counts unit tests only. The route components are covered by the Playwright suite, which this figure does not see, so the frontend line coverage understates what is exercised.
