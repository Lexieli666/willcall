/**
 * Measure the 5,000-seat render, in a browser that is not being instrumented.
 *
 * Why this is not in the Playwright suite, where it used to be: the same page renders in 19-43 ms
 * under a plain Chromium and 300-370 ms under a Playwright test, fifteen samples each, on the same
 * machine within a minute of each other. Playwright's fixtures inject and observe enough that on a
 * single React commit of five thousand elements the harness costs an order of magnitude more than
 * the thing being measured. The budget was failing on the tape measure.
 *
 * The end-to-end suite keeps the functional half - that five thousand real elements exist and the
 * application publishes its own measure - and the timing lives here, run by `make perf-seatmap`.
 * This is the same reasoning that put the SSE load generator in its own Node program.
 *
 * Lives under web/ because it needs that workspace's Playwright; scripts/measure-seatmap.sh
 * is the wrapper, and `make perf-seatmap` is the way to run it.
 */
import { chromium } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { execSync } from 'node:child_process'

const args = process.argv.slice(2)
const option = (name, fallback) => {
  const index = args.indexOf(`--${name}`)
  return index === -1 ? fallback : args[index + 1]
}

const base = option('base', process.env.WILLCALL_BASE_URL ?? 'http://127.0.0.1:8080')
const loads = Number(option('loads', '15'))
const budgetMs = Number(option('budget', '120'))

const response = await fetch(`${base}/api/events`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    venueName: 'Render budget',
    eventName: `Render budget ${new Date().toISOString()}`,
    holdTtlSeconds: 120,
    maxSeatsPerOrder: 4,
    status: 'ON_SALE',
    priceTiers: [{ name: 'Standard', amountCents: 4500, currency: 'USD' }],
    // 100 rows of 50 is a realistic shape for a five-thousand-seat room, and it exercises the
    // per-row memoisation rather than one enormous row.
    sections: [{ name: 'Floor', rowCount: 100, seatsPerRow: 50, priceTierName: 'Standard' }],
  }),
})
if (!response.ok) throw new Error(`could not create the event: ${response.status}`)
const event = await response.json()
if (event.capacity !== 5000) throw new Error(`expected 5000 seats, got ${event.capacity}`)

const browser = await chromium.launch()
const page = await browser.newPage()
const samples = []
for (let load = 0; load < loads; load += 1) {
  await page.goto(`${base}/events/${event.id}`)
  await page.waitForSelector('.wc-seatmap[data-seat-count="5000"]', { timeout: 30_000 })
  const measured = await page.evaluate(() => {
    const entries = performance.getEntriesByName('willcall:seatmap:render')
    const last = entries[entries.length - 1]
    return last ? last.duration : null
  })
  if (measured === null) throw new Error('the application published no willcall:seatmap:render measure')
  samples.push(Number(measured.toFixed(1)))
}
const seatCount = await page.locator('.wc-seat').count()
await browser.close()

const sorted = [...samples].sort((a, b) => a - b)
const at = (fraction) => sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)]
const report = {
  label: base.includes('127.0.0.1') || base.includes('localhost')
    ? 'local Docker Compose, not AWS'
    : base,
  commit: execSync('git rev-parse HEAD').toString().trim(),
  seats: 5000,
  seatElementsInDom: seatCount,
  loads,
  budgetMs,
  renderMs: at(0.5),
  p95Ms: at(0.95),
  bestMs: sorted[0],
  worstMs: sorted[sorted.length - 1],
  samples: sorted,
  method:
    `${loads} page loads in an uninstrumented Chromium, median reported. Measured with the ` +
    'Performance API from the moment the seat map data is available to the frame after the DOM ' +
    'is committed, so it covers building the tree and painting it, not the network. Run inside ' +
    'a Playwright test the same render measures 300-370 ms; the harness costs more than the ' +
    'render, which is why this is a separate program.',
}

const date = new Date().toISOString().slice(0, 10)
const out = `../load/results/${date}/seatmap-render`
mkdirSync(out, { recursive: true })
writeFileSync(`${out}/seatmap-render.json`, `${JSON.stringify(report, null, 2)}\n`)

console.log(`5,000-seat map, ${loads} loads, uninstrumented Chromium`)
console.log(`  median ${report.renderMs} ms   p95 ${report.p95Ms} ms   best ${report.bestMs} ms   worst ${report.worstMs} ms`)
console.log(`  budget ${budgetMs} ms -> ${report.renderMs < budgetMs ? 'met' : 'MISSED'}`)
console.log(`  seat elements in the DOM: ${report.seatElementsInDom}`)
console.log(`written to ${out.replace('../', '')}/seatmap-render.json`)
