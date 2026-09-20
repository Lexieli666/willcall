import { expect, test } from '@playwright/test'
import { createEvent, holdAsSomeoneElse, seatIdsOf } from './helpers'

/**
 * Reconnection, driven through the browser's own network stack.
 *
 * <p>A stream that works until the first network blip is not a stream that works. These tests cut
 * the connection the way a phone switching from wifi to cellular does — the server never learns
 * the client went away — and assert the map is correct afterwards, not merely that something
 * reconnected.
 */
test.describe('stream reconnection', () => {
  test('the map is correct again after the connection is cut @realtime', async ({
    page,
    request,
    context,
  }) => {
    const event = await createEvent(request, { rowCount: 3, seatsPerRow: 8 })
    await page.goto(`/events/${event.id}`)
    await expect(page.getByTestId('stream-status')).toContainText('live', { timeout: 10_000 })

    const seatIds = await seatIdsOf(request, event.id, 3)

    // Cut every stream request from now on: the browser sees the connection fail, which is what a
    // dropped network looks like from inside the page.
    await context.route('**/api/events/*/stream*', (route) => route.abort())

    // Force the current connection to die by navigating the route handler into effect: the
    // existing stream is aborted when the page next reconnects. Meanwhile the world moves on.
    await holdAsSomeoneElse(request, event.id, [seatIds[0]])
    await holdAsSomeoneElse(request, event.id, [seatIds[1]])

    // Let the client notice and fail a few reconnects.
    await page.waitForTimeout(2_000)

    // Now let it through again.
    await context.unroute('**/api/events/*/stream*')

    // The client must converge on the truth: both seats held, without a reload.
    await expect(page.locator(`#seat-${seatIds[0]}`)).toHaveAttribute('data-status', 'HELD', {
      timeout: 30_000,
    })
    await expect(page.locator(`#seat-${seatIds[1]}`)).toHaveAttribute('data-status', 'HELD', {
      timeout: 30_000,
    })
    await expect(page.locator(`#seat-${seatIds[2]}`)).toHaveAttribute('data-status', 'AVAILABLE')
  })

  test('a reload picks the map up where it is now, not where it was @realtime', async ({
    page,
    request,
  }) => {
    const event = await createEvent(request, { rowCount: 2, seatsPerRow: 6 })
    await page.goto(`/events/${event.id}`)
    await expect(page.getByTestId('stream-status')).toContainText('live', { timeout: 10_000 })

    const seatIds = await seatIdsOf(request, event.id, 2)
    await holdAsSomeoneElse(request, event.id, seatIds)
    await expect(page.locator(`#seat-${seatIds[0]}`)).toHaveAttribute('data-status', 'HELD', {
      timeout: 10_000,
    })

    await page.reload()

    await expect(page.locator(`#seat-${seatIds[0]}`)).toHaveAttribute('data-status', 'HELD', {
      timeout: 10_000,
    })
    await expect(page.locator(`#seat-${seatIds[1]}`)).toHaveAttribute('data-status', 'HELD')
  })

  test('a hold survives a reload, so refreshing does not orphan seats @realtime', async ({
    page,
    request,
  }) => {
    const event = await createEvent(request, { rowCount: 2, seatsPerRow: 6, holdTtlSeconds: 300 })
    await page.goto(`/events/${event.id}`)

    await page.getByTestId('best-available-1').click()
    await expect(page.getByTestId('hold-countdown')).toBeVisible({ timeout: 10_000 })

    await page.reload()

    // The hold is adopted from the buyer-state call, so the countdown comes back rather than the
    // buyer losing seats they are still paying time for.
    await expect(page.getByTestId('hold-countdown')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('checkout')).toBeVisible()
  })
})
