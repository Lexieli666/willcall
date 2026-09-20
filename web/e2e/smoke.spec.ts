import { expect, test } from '@playwright/test'
import { expectNoAxeViolations } from './axe'
import { createEvent } from './helpers'

test.describe('landing page', () => {
  test('lists what is on sale @smoke', async ({ page, request }) => {
    // A unique name, because every test in this suite creates events against the same database
    // and a locator that matches "Smoke Event" would match all of them.
    const name = `Smoke Event ${crypto.randomUUID().slice(0, 8)}`
    const event = await createEvent(request, { name, rowCount: 2, seatsPerRow: 5 })

    await page.goto('/')

    await expect(page.getByRole('heading', { level: 1, name: 'On sale now' })).toBeVisible()
    const card = page.locator('.wc-eventlist li').filter({ hasText: name })
    await expect(card.getByRole('link', { name })).toBeVisible()
    await expect(card).toContainText(`${event.capacity} seats`)
  })

  test('has no axe violations @a11y', async ({ page, request }, testInfo) => {
    await createEvent(request, { rowCount: 2, seatsPerRow: 5 })
    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expectNoAxeViolations(page, testInfo, 'landing')
  })

  test('the first tab stop is the skip link and it moves focus to main @keyboard', async ({ page }) => {
    await page.goto('/')
    await page.keyboard.press('Tab')
    const skip = page.getByRole('link', { name: 'Skip to main content' })
    await expect(skip).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.locator('#main')).toBeVisible()
  })

  test('an unknown route says so rather than showing a blank page @smoke', async ({ page }) => {
    await page.goto('/no-such-page')
    await expect(page.getByRole('heading', { name: 'No such page' })).toBeVisible()
  })
})
