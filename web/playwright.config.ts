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
  workers: process.env.CI ? 2 : undefined,
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
