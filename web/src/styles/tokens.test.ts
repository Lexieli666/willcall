import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { contrastRatio } from '../lib/contrast'

const here = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(join(here, 'tokens.css'), 'utf8')

/**
 * Reads the token values straight out of tokens.css for a given block, so this test fails if
 * somebody edits the stylesheet rather than only if they edit a duplicated table here.
 */
function tokensFrom(selector: string): Record<string, string> {
  const index = css.indexOf(selector)
  if (index < 0) throw new Error(`tokens.css has no block for ${selector}`)
  const open = css.indexOf('{', index)
  const close = css.indexOf('}', open)
  const body = css.slice(open + 1, close)
  const out: Record<string, string> = {}
  for (const line of body.split('\n')) {
    const match = /^\s*(--wc-[a-z0-9-]+):\s*(#[0-9a-fA-F]{3,8});/.exec(line)
    if (match?.[1] && match[2]) out[match[1]] = match[2]
  }
  return out
}

const TEXT_PAIRS: Array<[string, string, string]> = [
  ['body text', '--wc-fg', '--wc-bg'],
  ['muted text', '--wc-fg-muted', '--wc-bg'],
  ['muted text on raised', '--wc-fg-muted', '--wc-bg-raised'],
  ['accent button', '--wc-accent-fg', '--wc-accent'],
  ['available seat', '--wc-available-fg', '--wc-available-bg'],
  ['selected seat', '--wc-selected-fg', '--wc-selected-bg'],
  ['held seat', '--wc-held-fg', '--wc-held-bg'],
  ['sold seat', '--wc-sold-fg', '--wc-sold-bg'],
  ['blocked seat', '--wc-blocked-fg', '--wc-blocked-bg'],
  ['danger message', '--wc-danger-fg', '--wc-danger-bg'],
  ['success message', '--wc-success-fg', '--wc-success-bg'],
]

const NON_TEXT_PAIRS: Array<[string, string, string]> = [
  ['available seat border', '--wc-available-border', '--wc-available-bg'],
  ['held seat border', '--wc-held-border', '--wc-held-bg'],
  ['sold seat border', '--wc-sold-border', '--wc-sold-bg'],
  ['focus ring on page', '--wc-focus', '--wc-bg'],
  ['focus ring on raised', '--wc-focus', '--wc-bg-raised'],
]

describe.each([
  ['light theme', ':root {'],
  ['dark theme', ":root[data-theme='dark']"],
])('%s colour tokens', (_name, selector) => {
  const tokens = tokensFrom(selector)

  it.each(TEXT_PAIRS)('%s reaches 4.5:1', (_label, fg, bg) => {
    const fgValue = tokens[fg]
    const bgValue = tokens[bg]
    expect(fgValue, `${fg} missing from ${selector}`).toBeDefined()
    expect(bgValue, `${bg} missing from ${selector}`).toBeDefined()
    expect(contrastRatio(fgValue as string, bgValue as string)).toBeGreaterThanOrEqual(4.5)
  })

  it.each(NON_TEXT_PAIRS)('%s reaches the 3:1 non-text minimum', (_label, fg, bg) => {
    const fgValue = tokens[fg]
    const bgValue = tokens[bg]
    expect(fgValue, `${fg} missing from ${selector}`).toBeDefined()
    expect(bgValue, `${bg} missing from ${selector}`).toBeDefined()
    expect(contrastRatio(fgValue as string, bgValue as string)).toBeGreaterThanOrEqual(3)
  })
})
