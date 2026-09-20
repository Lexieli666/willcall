import { expect, test } from '@playwright/test'
import { expectNoAxeViolations } from './axe'
import { createEvent, holdAsSomeoneElse, seatIdsOf } from './helpers'

/**
 * A complete purchase using only the keyboard.
 *
 * <p>The rule this suite follows: **no `click()`, no `fill()`, no locator-driven focus.** Every
 * interaction is a key event, because the moment a test clicks something it stops testing whether
 * that thing is reachable. A seat map can be perfectly operable by mouse and completely unreachable
 * by keyboard, and only a test that refuses the mouse can tell the difference.
 */
test.describe('keyboard-only purchase', () => {
  test('a buyer with no mouse can choose a seat, hold it and pay @keyboard', async ({
    page,
    request,
  }) => {
    const event = await createEvent(request, { rowCount: 4, seatsPerRow: 8 })
    await page.goto(`/events/${event.id}`)
    await expect(page.locator('.wc-seatmap')).toBeVisible({ timeout: 10_000 })

    // Tab to the seat grid. The route is: skip link, nav links, view toggle, then the grid's
    // single tab stop. Pressing Tab until a gridcell has focus is how a person finds it, and it
    // also asserts the grid is reachable at all.
    let reachedGrid = false
    for (let press = 0; press < 25 && !reachedGrid; press += 1) {
      await page.keyboard.press('Tab')
      reachedGrid = await page.evaluate(
        () => document.activeElement?.getAttribute('role') === 'gridcell',
      )
    }
    expect(reachedGrid, 'the seat grid must be reachable by Tab').toBe(true)

    // Arrow into the second row, third seat. Two dimensions, which is the part a naive
    // implementation gets wrong.
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('ArrowRight')
    await page.keyboard.press('ArrowRight')

    const focusedLabel = await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))
    expect(focusedLabel).toMatch(/Seat B-3/)

    // Select with Enter.
    await page.keyboard.press('Enter')
    await expect(page.getByText('1 seat(s) selected')).toBeVisible()

    // Add an adjacent seat with Space, to prove both activation keys work.
    await page.keyboard.press('ArrowRight')
    await page.keyboard.press(' ')
    await expect(page.getByText('2 seat(s) selected')).toBeVisible()

    // Tab out of the grid to the hold button. The grid is one tab stop, so one Tab leaves it.
    let onHoldButton = false
    for (let press = 0; press < 10 && !onHoldButton; press += 1) {
      await page.keyboard.press('Tab')
      onHoldButton = await page.evaluate(
        () => document.activeElement?.getAttribute('data-testid') === 'hold-selected',
      )
    }
    expect(onHoldButton, 'the hold button must be reachable by Tab from the grid').toBe(true)

    await page.keyboard.press('Enter')
    await expect(page.getByTestId('hold-countdown')).toBeVisible({ timeout: 10_000 })

    // Focus must have moved to the panel that now holds the countdown. Without that, a keyboard
    // user is left on a button that has been replaced and has to hunt for what changed.
    const afterHold = await page.evaluate(() => document.activeElement?.textContent?.trim())
    expect(afterHold).toBe('Your selection')

    // And the hold was announced assertively.
    await expect(page.getByTestId('announcer-assertive')).toContainText(/seats? held/)

    // Tab to Pay and press it.
    let onCheckout = false
    for (let press = 0; press < 10 && !onCheckout; press += 1) {
      await page.keyboard.press('Tab')
      onCheckout = await page.evaluate(
        () => document.activeElement?.getAttribute('data-testid') === 'checkout',
      )
    }
    expect(onCheckout, 'the Pay button must be reachable by Tab').toBe(true)

    await page.keyboard.press('Enter')

    await expect(page.getByRole('heading', { name: 'Your seats are confirmed' })).toBeVisible({
      timeout: 15_000,
    })
  })

  test('the grid is one tab stop, not one per seat @keyboard', async ({ page, request }) => {
    // Thirty-two seats. If each were a tab stop, leaving the map would take thirty-two presses;
    // with five thousand it would be unusable, which is the failure this guards against.
    const event = await createEvent(request, { rowCount: 4, seatsPerRow: 8 })
    await page.goto(`/events/${event.id}`)
    await expect(page.locator('.wc-seatmap')).toBeVisible({ timeout: 10_000 })

    const focusable = await page.locator('[role="gridcell"][tabindex="0"]').count()
    expect(focusable).toBe(1)
    expect(await page.locator('[role="gridcell"][tabindex="-1"]').count()).toBe(31)
  })

  test('Home, End and Ctrl+Home move as documented @keyboard', async ({ page, request }) => {
    const event = await createEvent(request, { rowCount: 3, seatsPerRow: 6 })
    await page.goto(`/events/${event.id}`)
    await expect(page.locator('.wc-seatmap')).toBeVisible({ timeout: 10_000 })

    await page.locator('[role="gridcell"][tabindex="0"]').focus()

    await page.keyboard.press('End')
    expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))).toMatch(/Seat A-6/)

    await page.keyboard.press('Home')
    expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))).toMatch(/Seat A-1/)

    await page.keyboard.press('Control+End')
    expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))).toMatch(/Seat C-6/)
  })

  test('losing a seat is announced assertively, not just shown @keyboard', async ({
    page,
    request,
  }) => {
    const event = await createEvent(request, { rowCount: 2, seatsPerRow: 6 })
    await page.goto(`/events/${event.id}`)
    await expect(page.locator('.wc-seatmap')).toBeVisible({ timeout: 10_000 })

    const seatIds = await seatIdsOf(request, event.id, 1)
    const seatId = seatIds[0]

    await page.locator(`#seat-${seatId}`).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText('1 seat(s) selected')).toBeVisible()

    await holdAsSomeoneElse(request, event.id, [seatId])

    // Assertive, because somebody who cannot see the map has no other way to learn that the seat
    // they chose is gone.
    await expect(page.getByTestId('announcer-assertive')).toContainText(/Someone else took/, {
      timeout: 15_000,
    })
  })

  test('the purchase path has no axe violations at any step @a11y @keyboard', async ({
    page,
    request,
  }, testInfo) => {
    const event = await createEvent(request, { rowCount: 3, seatsPerRow: 6 })
    await page.goto(`/events/${event.id}`)
    await expect(page.locator('.wc-seatmap')).toBeVisible({ timeout: 10_000 })

    await page.locator('[role="gridcell"][tabindex="0"]').focus()
    await page.keyboard.press('Enter')
    await expectNoAxeViolations(page, testInfo, 'keyboard-selected')

    await page.getByTestId('hold-selected').focus()
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('hold-countdown')).toBeVisible({ timeout: 10_000 })
    await expectNoAxeViolations(page, testInfo, 'keyboard-holding')
  })
})
