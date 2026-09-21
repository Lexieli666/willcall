import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.WILLCALL_E2E_BASE_URL ?? 'http://127.0.0.1:4173'

/**
 * The e2e suite runs against the production build served by `vite preview`, not the dev
 * server, so the axe and Lighthouse results describe what a user would actually receive.
 * When WILLCALL_E2E_BASE_URL is set (CI against docker compose), no local server is started.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // Four, not one-per-core. Every test in this suite talks to the same three-replica stack, so
  // the parallelism is self-inflicted contention rather than speed: with the default (half the
  // cores, sixteen here) the reconnect test took 32.6 s and failed waiting for a stream to come
  // back, and passed in 2.7 s on its own. Four keeps the suite under twenty seconds and leaves
  // the realtime tests measuring the application instead of the queue in front of it.
  workers: process.env.CI ? 2 : 4,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['json', { outputFile: 'playwright-report/results.json' }]]
    : [['list'], ['html', { open: 'never' }]],
  timeout: 45_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: process.env.WILLCALL_E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run build && npm run preview',
        url: 'http://127.0.0.1:4173',
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
      },
})
