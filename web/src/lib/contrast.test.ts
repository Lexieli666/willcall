import { describe, expect, it } from 'vitest'
import { contrastRatio, parseHex, relativeLuminance } from './contrast'

describe('contrast maths', () => {
  it('matches the WCAG reference values at the extremes', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5)
    expect(contrastRatio('#ffffff', '#ffffff')).toBeCloseTo(1, 5)
  })

  it('is symmetric', () => {
    expect(contrastRatio('#0b5bd3', '#ffffff')).toBeCloseTo(contrastRatio('#ffffff', '#0b5bd3'), 10)
  })

  it('expands three-digit hex', () => {
    expect(parseHex('#fff')).toEqual([255, 255, 255])
  })

  it('rejects a non-colour instead of silently returning black', () => {
    expect(() => relativeLuminance('rebeccapurple')).toThrow(/not a hex colour/)
  })
})
