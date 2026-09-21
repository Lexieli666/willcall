import { expect, test } from '@playwright/test'
import { expectNoAxeViolations } from './axe'
import { createEvent, expireAllHolds, holdAsSomeoneElse, seatIdsOf } from './helpers'

test.describe('buying seats', () => {
  test('pick seats, hold them, pay, and land on a confirmation @smoke', async ({ page, request }) => {
    const event = await createEvent(request, { rowCount: 4, seatsPerRow: 10 })
    await page.goto(`/events/${event.id}`)

    const seatIds = await seatIdsOf(request, event.id, 2)
    await page.locator(`#seat-${seatIds[0]}`).click()
    await page.locator(`#seat-${seatIds[1]}`).click()

    await expect(page.getByText('2 seat(s) selected')).toBeVisible()

    await page.getByTestId('hold-selected').click()
    await expect(page.getByTestId('hold-countdown')).toBeVisible()

    await page.getByTestId('checkout').click()

    await expect(page.getByRole('heading', { name: 'Your seats are confirmed' })).toBeVisible({
      timeout: 15_000,
    })
    await expect(page.getByText('CONFIRMED', { exact: true })).toBeVisible()
  })

  test('best available takes seats without touching the map @smoke', async ({ page, request }) => {
    const event = await createEvent(request, { rowCount: 3, seatsPerRow: 10 })
    await page.goto(`/events/${event.id}`)

    await page.getByTestId('best-available-1').click()

    await expect(page.getByTestId('hold-countdown')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('event-message')).toContainText('1 seat(s) held.')
  })

  test('a seat taken between render and click is refused by name, not silently swapped', async ({
    page,
    request,
  }) => {
    const event = await createEvent(request, { rowCount: 2, seatsPerRow: 6 })
    await page.goto(`/events/${event.id}`)

    const seatIds = await seatIdsOf(request, event.id, 1)
    const seatId = seatIds[0]

    await page.locator(`#seat-${seatId}`).click()
    await expect(page.getByText('1 seat(s) selected')).toBeVisible()

    // Somebody else takes it while this buyer is deciding.
    await holdAsSomeoneElse(request, event.id, [seatId])

    // The buyer is told which seat went, and it leaves the selection. What must not happen is a
    // different seat quietly appearing in its place.
    // The visible message and the announcer both carry this text; assert on the one a
    // sighted buyer reads. The announcer has its own assertions in keyboard.spec.ts.
    await expect(page.getByTestId('event-message')).toContainText(/Someone else took/, {
      timeout: 10_000,
    })
    await expect(page.getByText('No seats selected')).toBeVisible()
  })

  test('an expired hold disables checkout and says why @realtime', async ({ page, request }) => {
    const event = await createEvent(request, { rowCount: 2, seatsPerRow: 6, holdTtlSeconds: 15 })
    await page.goto(`/events/${event.id}`)

    await page.getByTestId('best-available-1').click()
    await expect(page.getByTestId('hold-countdown')).toBeVisible({ timeout: 10_000 })

    await expireAllHolds(request)

    // The server-side sweeper releases the seats; the client's own countdown reaches zero.
    await expect(page.getByTestId('event-message')).toContainText(/expired/, { timeout: 30_000 })
    await expect(page.getByTestId('checkout')).toHaveCount(0)
  })

  test('the text list mode offers the same seats as the map @a11y', async ({ page, request }, testInfo) => {
    const event = await createEvent(request, { rowCount: 3, seatsPerRow: 8 })
    await page.goto(`/events/${event.id}`)

    await page.getByRole('button', { name: 'Text list' }).click()

    await expect(page.getByRole('status').filter({ hasText: 'seats match' })).toContainText('24 seats match')
    await expectNoAxeViolations(page, testInfo, 'seat-list-mode')

    await page.getByRole('button', { name: /row A, seat 1/ }).click()
    await expect(page.getByText('1 seat(s) selected')).toBeVisible()
  })

  test('every route and state is free of axe violations @a11y', async ({ page, request }, testInfo) => {
    const event = await createEvent(request, { rowCount: 3, seatsPerRow: 8 })

    await page.goto('/')
    await expectNoAxeViolations(page, testInfo, 'events-list')

    await page.goto(`/events/${event.id}`)
    await expect(page.getByRole('grid').first()).toBeVisible()
    await expectNoAxeViolations(page, testInfo, 'event-map-empty-selection')

    const seatIds = await seatIdsOf(request, event.id, 2)
    await page.locator(`#seat-${seatIds[0]}`).click()
    await expectNoAxeViolations(page, testInfo, 'event-map-with-selection')

    await page.getByTestId('hold-selected').click()
    await expect(page.getByTestId('hold-countdown')).toBeVisible()
    await expectNoAxeViolations(page, testInfo, 'event-holding')

    await page.getByTestId('checkout').click()
    await expect(page.getByRole('heading', { name: 'Your seats are confirmed' })).toBeVisible({
      timeout: 15_000,
    })
    await expectNoAxeViolations(page, testInfo, 'confirmation')

    await page.goto('/organizer')
    await expect(page.getByRole('heading', { name: 'Organizer dashboard' })).toBeVisible()
    await expectNoAxeViolations(page, testInfo, 'organizer')

    await page.goto('/status')
    await expectNoAxeViolations(page, testInfo, 'status')
  })
})
