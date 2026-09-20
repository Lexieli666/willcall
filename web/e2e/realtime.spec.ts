import { expect, test } from '@playwright/test'
import { createEvent, holdAsSomeoneElse, injectGap, seatIdsOf } from './helpers'

/**
 * The real-time protocol, exercised against the real server.
 *
 * <p>The gap test is the one that matters. Faking a dropped message in the browser would prove
 * the fake works; burning a sequence number on the server makes a message genuinely go missing,
 * and the real client, over the real transport, has to notice and recover.
 */
test.describe('live seat updates', () => {
  test('a seat taken by somebody else turns up on the map without a reload @realtime', async ({
    page,
    request,
  }) => {
    const event = await createEvent(request, { rowCount: 3, seatsPerRow: 8 })
    await page.goto(`/events/${event.id}`)

    const seatIds = await seatIdsOf(request, event.id, 1)
    const seatId = seatIds[0]
    const seat = page.locator(`#seat-${seatId}`)

    await expect(seat).toHaveAttribute('data-status', 'AVAILABLE')

    await holdAsSomeoneElse(request, event.id, [seatId])

    // No reload, no polling: the change has to arrive on the stream.
    await expect(seat).toHaveAttribute('data-status', 'HELD', { timeout: 10_000 })
  })

  test('a missing sequence number is detected and the client resyncs @realtime', async ({
    page,
    request,
  }) => {
    const event = await createEvent(request, { rowCount: 3, seatsPerRow: 8 })
    await page.goto(`/events/${event.id}`)
    await expect(page.getByTestId('stream-status')).toContainText('live', { timeout: 10_000 })

    const seatIds = await seatIdsOf(request, event.id, 3)

    // One real change first, so the client has a cursor to be wrong about.
    await holdAsSomeoneElse(request, event.id, [seatIds[0]])
    await expect(page.locator(`#seat-${seatIds[0]}`)).toHaveAttribute('data-status', 'HELD', {
      timeout: 10_000,
    })

    // Now make a message vanish, then cause another change. The client should see sequence N+2
    // where it expected N+1.
    await injectGap(request, event.id)
    await holdAsSomeoneElse(request, event.id, [seatIds[1]])

    // The recovery is what is asserted: the second seat's state arrives correctly despite the
    // hole, which can only happen if the client noticed and re-fetched.
    await expect(page.locator(`#seat-${seatIds[1]}`)).toHaveAttribute('data-status', 'HELD', {
      timeout: 15_000,
    })

    // And the client says so, rather than recovering silently.
    const diagnostics = page.getByTestId('stream-status')
    await expect(diagnostics).toContainText(/gaps recovered/)
  })

  test('the organizer dashboard counts the same seats the buyers see @realtime', async ({
    page,
    request,
  }) => {
    const event = await createEvent(request, { rowCount: 4, seatsPerRow: 10 })
    await page.goto('/organizer')

    await page.getByLabel('Event').selectOption(event.id)

    // Locate the value by its own term rather than by matching a bare number, which would also
    // match the capacity, the sequence counter and anything else that happened to be 3.
    const onHold = page
      .locator('dt', { hasText: /^On hold$/ })
      .locator('xpath=following-sibling::dd[1]')
    await expect(onHold).toHaveText('0', { timeout: 10_000 })

    const seatIds = await seatIdsOf(request, event.id, 3)
    await holdAsSomeoneElse(request, event.id, seatIds)

    await expect(onHold).toHaveText('3', { timeout: 10_000 })

    const available = page
      .locator('dt', { hasText: /^Available$/ })
      .locator('xpath=following-sibling::dd[1]')
    await expect(available).toHaveText('37')
  })
})
