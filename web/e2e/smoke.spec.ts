import { expect, test } from '@playwright/test'
import { expectNoAxeViolations } from './axe'

test.describe('landing page', () => {
  test('serves a real page with the product name and scope @smoke', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1, name: 'Willcall' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Service status' })).toBeVisible()
  })

  test('has no axe violations @a11y', async ({ page }, testInfo) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expectNoAxeViolations(page, testInfo, 'landing')
  })

  test('the first tab stop is the skip link and it moves focus to main @keyboard', async ({
    page,
  }) => {
    await page.goto('/')
    await page.keyboard.press('Tab')
    const skip = page.getByRole('link', { name: 'Skip to main content' })
    await expect(skip).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.locator('#main')).toBeVisible()
  })
})
