import { render, screen } from '@testing-library/react'
import { act } from 'react'
import { describe, expect, it } from 'vitest'
import { LiveAnnouncer } from './LiveAnnouncer'
import { Announcer } from '../lib/announcer'

describe('LiveAnnouncer', () => {
  it('renders both regions from first paint, empty', () => {
    // A live region added at the moment it has something to say is usually missed: the screen
    // reader has to be observing the region before the change, not with it.
    render(<LiveAnnouncer announcer={new Announcer()} />)

    expect(screen.getByTestId('announcer-assertive')).toBeEmptyDOMElement()
    expect(screen.getByTestId('announcer-polite')).toBeEmptyDOMElement()
    expect(screen.getByTestId('announcer-assertive')).toHaveAttribute('aria-live', 'assertive')
    expect(screen.getByTestId('announcer-polite')).toHaveAttribute('aria-live', 'polite')
  })

  it('routes a critical announcement to the assertive region only', () => {
    const announcer = new Announcer()
    render(<LiveAnnouncer announcer={announcer} />)

    act(() => announcer.critical('Your hold expired'))

    expect(screen.getByTestId('announcer-assertive')).toHaveTextContent('Your hold expired')
    expect(screen.getByTestId('announcer-polite')).toBeEmptyDOMElement()
  })

  it('routes an ambient announcement to the polite region only', () => {
    const announcer = new Announcer(() => 0)
    render(<LiveAnnouncer announcer={announcer} />)

    act(() => {
      announcer.ambient(1412)
    })

    expect(screen.getByTestId('announcer-polite')).toHaveTextContent('1,412 seats still available')
    expect(screen.getByTestId('announcer-assertive')).toBeEmptyDOMElement()
  })

  it('re-renders for a repeated message, so the second occurrence is not silent', () => {
    const announcer = new Announcer()
    const { container } = render(<LiveAnnouncer announcer={announcer} />)

    act(() => announcer.critical('Seat A-1 was taken'))
    const first = container.querySelector('[data-testid="announcer-assertive"] span')

    act(() => announcer.critical('Seat A-1 was taken'))
    const second = container.querySelector('[data-testid="announcer-assertive"] span')

    // A live region announces when its content changes. The same string twice is not a change, so
    // the keyed child is what forces a real DOM change and a second announcement.
    expect(first).not.toBe(second)
    expect(screen.getByTestId('announcer-assertive')).toHaveTextContent('Seat A-1 was taken')
  })

  it('is visually hidden but present in the accessibility tree', () => {
    render(<LiveAnnouncer announcer={new Announcer()} />)

    // Not display:none and not aria-hidden — either would remove it from the accessibility tree
    // and make the whole mechanism silent.
    const region = screen.getByTestId('announcer-assertive')
    expect(region).toHaveClass('wc-visually-hidden')
    expect(region).not.toHaveAttribute('aria-hidden')
  })
})
