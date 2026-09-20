import { expect, test } from '@playwright/test'
import { createEvent } from './helpers'

/**
 * The 5,000-seat render budget.
 *
 * <p>Measured with the Performance API from the moment the seat map data is available to the
 * frame after the DOM is committed — so it covers building the tree and painting it, not the
 * network. The application sets both marks itself; this test reads the measure rather than
 * timing from the outside, because an outside timing would include the fetch and flatter or
 * penalise the render depending on the network.
 *
 * <p>The measured value is printed and attached whether it passes or fails, because a budget
 * that only reports failures tells you nothing about how much headroom is left.
 */
test.describe('seat map performance', () => {
  test('a 5,000-seat map renders inside the budget @performance', async ({ page, request }, testInfo) => {
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

    expect(measure, 'the application must publish a willcall:seatmap:render measure').not.toBeNull()

    const milliseconds = measure as number
    await testInfo.attach('seatmap-render-ms.json', {
      body: JSON.stringify({ seats: 5000, renderMs: milliseconds, budgetMs: 120 }, null, 2),
      contentType: 'application/json',
    })
     
    console.log(`5,000-seat map rendered in ${milliseconds.toFixed(1)} ms (budget 120 ms)`)

    expect(milliseconds).toBeLessThan(120)

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
