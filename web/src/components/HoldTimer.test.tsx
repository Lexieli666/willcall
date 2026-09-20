import { render, screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HoldTimer } from './HoldTimer'

const NOW = new Date('2026-09-20T12:00:00.000Z')

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

function advance(seconds: number) {
  act(() => {
    vi.advanceTimersByTime(seconds * 1000)
  })
}

describe('HoldTimer', () => {
  it('counts down from the server-reported expiry', () => {
    render(
      <HoldTimer
        expiresAt="2026-09-20T12:02:05.000Z"
        serverTime={NOW.toISOString()}
        onExpired={vi.fn()}
      />,
    )

    expect(screen.getByTestId('hold-countdown')).toHaveTextContent('2:05')
    advance(65)
    expect(screen.getByTestId('hold-countdown')).toHaveTextContent('1:00')
  })

  it('uses the server clock, so a device whose clock is wrong still sees the truth', () => {
    // The device believes it is two minutes earlier than the server does. A timer trusting the
    // device would show two extra minutes the buyer does not have.
    const deviceSkewed = new Date(NOW.getTime() - 120_000)
    vi.setSystemTime(deviceSkewed)

    render(
      <HoldTimer
        expiresAt="2026-09-20T12:01:00.000Z"
        serverTime={NOW.toISOString()}
        onExpired={vi.fn()}
      />,
    )

    expect(screen.getByTestId('hold-countdown')).toHaveTextContent('1:00')
  })

  it('calls onExpired exactly once when it reaches zero', () => {
    const onExpired = vi.fn()
    render(
      <HoldTimer
        expiresAt="2026-09-20T12:00:03.000Z"
        serverTime={NOW.toISOString()}
        onExpired={onExpired}
      />,
    )

    advance(5)
    expect(onExpired).toHaveBeenCalledTimes(1)

    advance(10)
    expect(onExpired).toHaveBeenCalledTimes(1)
  })

  it('never shows a negative countdown', () => {
    render(
      <HoldTimer
        expiresAt="2026-09-20T12:00:01.000Z"
        serverTime={NOW.toISOString()}
        onExpired={vi.fn()}
      />,
    )

    advance(30)
    expect(screen.getByTestId('hold-countdown')).toHaveTextContent('0:00')
  })

  it('announces in words, because a screen reader reads 2:05 as a time of day', () => {
    render(
      <HoldTimer
        expiresAt="2026-09-20T12:01:00.000Z"
        serverTime={NOW.toISOString()}
        onExpired={vi.fn()}
      />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('1 minute left to complete your purchase')
  })

  it('does not announce every second, which would make a screen reader unusable', () => {
    render(
      <HoldTimer
        expiresAt="2026-09-20T12:02:00.000Z"
        serverTime={NOW.toISOString()}
        onExpired={vi.fn()}
      />,
    )

    // 120 s: on a thirty-second boundary, so it speaks.
    expect(screen.getByRole('status')).not.toBeEmptyDOMElement()

    // 119 s: not a threshold, so the live region goes quiet rather than interrupting.
    advance(1)
    expect(screen.getByRole('status')).toBeEmptyDOMElement()

    // 90 s: a thirty-second boundary again.
    advance(29)
    expect(screen.getByRole('status')).toHaveTextContent('1 minute 30 seconds')
  })

  it('speaks every second in the last ten, when a second matters', () => {
    render(
      <HoldTimer
        expiresAt="2026-09-20T12:00:09.000Z"
        serverTime={NOW.toISOString()}
        onExpired={vi.fn()}
      />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('9 seconds')
    advance(1)
    expect(screen.getByRole('status')).toHaveTextContent('8 seconds')
    advance(1)
    expect(screen.getByRole('status')).toHaveTextContent('7 seconds')
  })

  it('marks itself urgent in the last thirty seconds', () => {
    const { container } = render(
      <HoldTimer
        expiresAt="2026-09-20T12:00:45.000Z"
        serverTime={NOW.toISOString()}
        onExpired={vi.fn()}
      />,
    )

    expect(container.querySelector('.wc-holdtimer--urgent')).toBeNull()
    advance(20)
    expect(container.querySelector('.wc-holdtimer--urgent')).not.toBeNull()
  })
})
