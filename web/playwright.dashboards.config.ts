import { defineConfig, devices } from '@playwright/test'

/**
 * A separate config for the dashboard captures, not another project inside the e2e config.
 *
 * The e2e suite has to be runnable with nothing but the application up; these need Prometheus and
 * Grafana as well. Putting them in the same config would mean either `npx playwright test` failing
 * for anyone without the observability profile, or a `grep -v` in CI that quietly stops running
 * something else one day.
 */
export default defineConfig({
  testDir: './dashboards',
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  // Grafana renders a panel after its query returns, and a query over a three-minute window on a
  // cold Prometheus is not instant.
  timeout: 180_000,
  expect: { timeout: 60_000 },
  use: {
    baseURL: process.env.WILLCALL_GRAFANA_URL ?? 'http://127.0.0.1:3000',
    ...devices['Desktop Chrome'],
    viewport: { width: 1600, height: 1200 },
    // Deterministic images: a screenshot that differs between runs because of an animation frame
    // makes every commit that touches it look like a change.
    deviceScaleFactor: 2,
  },
})
