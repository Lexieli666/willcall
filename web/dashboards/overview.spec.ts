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

  // Grafana renders only the panels near the viewport, so a full-page screenshot taken without
  // scrolling captures the top of the dashboard and blank space where the rest should be. The
  // first capture showed ten panels of fifteen and the close-up test could not find the eleventh
  // at all. Scroll to the bottom, let everything load, then go back to the top.
  await scrollWholeDashboard(page)

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
  await scrollWholeDashboard(page)
  await page.waitForFunction(() => document.querySelectorAll('[aria-label="Panel loading bar"]').length === 0, null, {
    timeout: 120_000,
  })

  for (const [title, file] of shots) {
    // Grafana only renders panels near the viewport, so a panel below the fold does not exist in
    // the DOM until it is scrolled to. Waiting for it to become visible therefore times out on
    // exactly the panels that are furthest down - "Database pool saturation" was the first one
    // past the fold and the only one that failed.
    const panel = page.locator('[data-testid^="data-testid Panel header"]', { hasText: title }).first()
    await panel.scrollIntoViewIfNeeded()
    const container = panel.locator('xpath=ancestor::*[contains(@class, "react-grid-item")]').first()
    await expect(container).toBeVisible()
    // Scrolling starts a query for a panel that had not loaded yet; let it finish before capturing.
    await page.waitForFunction(
      () => document.querySelectorAll('[aria-label="Panel loading bar"]').length === 0,
      null,
      { timeout: 60_000 },
    )
    await container.screenshot({ path: join(imageDir, file) })
  }
})

/**
 * Puts every panel in the DOM.
 *
 * Grafana renders only the panels near the viewport, so a full-page screenshot taken without
 * scrolling captures the top of the dashboard and blank space where the rest should be - ten
 * panels of fifteen, the first time this ran.
 */
async function scrollWholeDashboard(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async () => {
    const scroller = document.querySelector('.scrollbar-view') ?? document.scrollingElement
    if (!scroller) return
    for (let y = 0; y <= scroller.scrollHeight; y += 400) {
      scroller.scrollTop = y
      await new Promise((resolve) => setTimeout(resolve, 120))
    }
    scroller.scrollTop = 0
  })
}
