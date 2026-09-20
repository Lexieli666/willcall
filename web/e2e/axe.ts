import AxeBuilder from '@axe-core/playwright'
import { expect, type Page, type TestInfo } from '@playwright/test'

/**
 * One helper so every route is scanned with the same rule set. WCAG 2.1 AA plus best
 * practices; nothing is excluded, because an excluded rule is a violation nobody sees.
 * Violations are attached to the report so a CI failure says which node failed.
 */
export async function expectNoAxeViolations(page: Page, testInfo: TestInfo, label: string) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'])
    .analyze()

  await testInfo.attach(`axe-${label}.json`, {
    body: JSON.stringify(results.violations, null, 2),
    contentType: 'application/json',
  })

  const summary = results.violations.map((v) => `${v.id} (${v.nodes.length} nodes): ${v.help}`)
  expect(summary, `axe violations on ${label}`).toEqual([])
}
