import { describe, expect, it } from 'vitest'
import { formatCountdown, formatMoney, spokenCountdown } from './money'

describe('money and time formatting', () => {
  it('formats cents without floating point drift', () => {
    expect(formatMoney(2500, 'USD', 'en-US')).toBe('$25.00')
    expect(formatMoney(0, 'USD', 'en-US')).toBe('$0.00')
    expect(formatMoney(199_99, 'USD', 'en-US')).toBe('$199.99')
  })

  it('counts down in minutes and seconds', () => {
    expect(formatCountdown(125)).toBe('2:05')
    expect(formatCountdown(60)).toBe('1:00')
    expect(formatCountdown(9)).toBe('0:09')
  })

  it('never shows a negative countdown, because an expired hold is 0:00 not -0:03', () => {
    expect(formatCountdown(-3)).toBe('0:00')
    expect(spokenCountdown(-3)).toBe('0 seconds')
  })

  it('spells the countdown out for a screen reader, which reads 2:05 as a time of day', () => {
    expect(spokenCountdown(125)).toBe('2 minutes 5 seconds')
    expect(spokenCountdown(60)).toBe('1 minute')
    expect(spokenCountdown(1)).toBe('1 second')
    expect(spokenCountdown(0)).toBe('0 seconds')
  })
})

describe('formatter caching', () => {
  it('reuses the formatter for the same locale and currency', () => {
    // Not a micro-optimisation: building an Intl.NumberFormat per seat was the largest single
    // cost in rendering a five-thousand-seat map, and it pushed the render over its budget.
    const start = performance.now()
    for (let i = 0; i < 5000; i += 1) formatMoney(2500 + i, 'USD', 'en-US')
    const elapsed = performance.now() - start

    expect(formatMoney(2500, 'USD', 'en-US')).toBe('$25.00')
    expect(elapsed).toBeLessThan(60)
  })

  it('still distinguishes currencies', () => {
    expect(formatMoney(2500, 'USD', 'en-US')).toBe('$25.00')
    expect(formatMoney(2500, 'EUR', 'en-US')).toBe('€25.00')
  })
})
