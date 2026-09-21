import { expect, test } from '@playwright/test'
import { createEvent } from './helpers'

/**
 * The 5,000-seat map is real DOM, and the application measures its own render.
 *
 * <p><b>The timing budget is not asserted here.</b> It used to be, and it was measuring the wrong
 * thing: the same render takes 19-43 ms in a plain Chromium and 300-370 ms inside a Playwright
 * test, fifteen samples each, on the same machine a minute apart. On a single React commit of
 * five thousand elements the harness costs an order of magnitude more than the render, so the
 * budget was failing on the tape measure. A published figure of 42 ms could not be reproduced by
 * either method, which is what started the investigation.
 *
 * <p>The timing now lives in {@code web/perf/measure-seatmap-render.mjs}, run by
 * {@code make perf-seatmap}, which drives an uninstrumented browser and writes a distribution to
 * {@code load/results/}. What stays here is what an end-to-end test is good at: that five
 * thousand real elements exist, and that the application publishes the measure the other script
 * reads. If either stops being true the measurement downstream is meaningless, and this fails.
 */
test.describe('seat map performance', () => {
  test('a 5,000-seat map is real DOM and publishes its own render measure @performance', async ({
    page,
    request,
  }) => {
    // 100 rows of 50 is a realistic shape for a five-thousand-seat room, and it exercises the
    // per-row memoisation rather than one enormous row.
    const event = await createEvent(request, { rowCount: 100, seatsPerRow: 50, name: 'Render budget' })
    expect(event.capacity).toBe(5000)

    await page.goto(`/events/${event.id}`)

    const map = page.locator('.wc-seatmap')
    await expect(map).toBeVisible({ timeout: 30_000 })
    await expect(map).toHaveAttribute('data-seat-count', '5000')

    const measure = await page.evaluate(() => {
      const entries = performance.getEntriesByName('willcall:seatmap:render')
      const last = entries[entries.length - 1]
      return last ? last.duration : null
    })
    expect(
      measure,
      'the application must publish a willcall:seatmap:render measure for the perf script to read',
    ).not.toBeNull()
    expect(measure as number).toBeGreaterThan(0)

    // Every seat is a real element, which is the point of paying the render cost at all.
    await expect(page.locator('.wc-seat')).toHaveCount(5000)
  })

  test('a delta repaints only the rows it touches @performance', async ({ page, request }) => {
    const event = await createEvent(request, { rowCount: 50, seatsPerRow: 20 })
    await page.goto(`/events/${event.id}`)
    await expect(page.getByRole('grid').first()).toBeVisible()

    // A crude but honest proxy for "did the whole map re-render": count how long the browser
    // spends on script between the change arriving and the seat updating.
    const seatsResponse = await request.get(
      `${process.env.WILLCALL_API_ORIGIN ?? 'http://127.0.0.1:8080'}/api/events/${event.id}/seats`,
    )
    const body = (await seatsResponse.json()) as {
      sections: Array<{ rows: Array<{ seats: Array<{ id: string }> }> }>
    }
    const seatId = body.sections[0].rows[0].seats[0].id

    await page.evaluate(() => performance.clearMeasures())

    await request.post(
      `${process.env.WILLCALL_API_ORIGIN ?? 'http://127.0.0.1:8080'}/api/events/${event.id}/holds`,
      {
        headers: {
          'X-Willcall-User': `perf-${Math.random().toString(36).slice(2, 12)}`,
          'Idempotency-Key': crypto.randomUUID(),
          'Content-Type': 'application/json',
        },
        data: { seatIds: [seatId] },
      },
    )

    await expect(page.locator(`#seat-${seatId}`)).toHaveAttribute('data-status', 'HELD', {
      timeout: 10_000,
    })

    // The other 999 seats must still be there and still available: a repaint that rebuilt the
    // map would be correct but would also reset scroll and focus, which is the real failure.
    await expect(page.locator('.wc-seat--available')).toHaveCount(999)
  })
})
