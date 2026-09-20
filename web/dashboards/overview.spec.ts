import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const imageDir = process.env.WILLCALL_IMAGE_DIR ?? '../docs/images'
const dashboard = '/d/willcall-overview/willcall-service-overview'

// A window wide enough to contain the load run that just finished, and `kiosk` so the screenshot
// is the dashboard rather than Grafana's chrome around it.
const params = '?orgId=1&from=now-15m&to=now&kiosk&refresh='

test.beforeAll(() => {
  mkdirSync(imageDir, { recursive: true })
})

test('the service overview has data on it', async ({ page }) => {
  await page.goto(`${dashboard}${params}`)

  // Wait for panels to finish querying rather than for a fixed time: Grafana marks a loading panel
  // with a spinner, and a screenshot taken during one is a picture of a spinner.
  await expect(page.getByText('Requests per second by endpoint (the R of RED)')).toBeVisible()
  await page.waitForFunction(() => document.querySelectorAll('[aria-label="Panel loading bar"]').length === 0, null, {
    timeout: 120_000,
  })

  // "No data" on every panel means the capture ran against an idle stack, which is the failure
  // this test exists to prevent — the image would look fine and prove nothing.
  const noData = await page.getByText('No data', { exact: true }).count()
  const panels = await page.locator('[data-testid^="data-testid Panel header"]').count()
  expect(panels, 'the dashboard should have panels').toBeGreaterThan(8)
  expect(noData, `${noData} of ${panels} panels have no data; run load before capturing`).toBeLessThan(
    Math.ceil(panels / 2),
  )

  await page.screenshot({ path: join(imageDir, 'grafana-overview.png'), fullPage: true })
})

test('the RED panels and the pool, close up', async ({ page }) => {
  const shots: Array<[string, string]> = [
    ['Latency percentiles on the hold path (the D of RED)', 'grafana-hold-latency.png'],
    ['Database pool saturation', 'grafana-pool-saturation.png'],
    ['Open SSE connections by replica', 'grafana-sse-connections.png'],
  ]
  await page.goto(`${dashboard}${params}`)
  await page.waitForFunction(() => document.querySelectorAll('[aria-label="Panel loading bar"]').length === 0, null, {
    timeout: 120_000,
  })

  for (const [title, file] of shots) {
    const panel = page.locator('[data-testid^="data-testid Panel header"]', { hasText: title }).first()
    const container = panel.locator('xpath=ancestor::*[contains(@class, "react-grid-item")]').first()
    await expect(container).toBeVisible()
    await container.screenshot({ path: join(imageDir, file) })
  }
})
