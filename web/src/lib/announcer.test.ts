import { describe, expect, it, vi } from 'vitest'
import { AMBIENT_INTERVAL_MS, Announcer, type Announcement } from './announcer'

function collector() {
  const announcements: Announcement[] = []
  return { announcements, listener: (a: Announcement) => announcements.push(a) }
}

describe('Announcer', () => {
  it('announces the buyer\'s own state changes immediately and assertively', () => {
    const { announcements, listener } = collector()
    const announcer = new Announcer()
    announcer.subscribe(listener)

    announcer.critical('Your hold expired')

    expect(announcements).toEqual([
      { message: 'Your hold expired', politeness: 'assertive', sequence: 1 },
    ])
  })

  it('re-announces an identical critical message, because it happened twice', () => {
    const { announcements, listener } = collector()
    const announcer = new Announcer()
    announcer.subscribe(listener)

    announcer.critical('Seat A-1 was taken')
    announcer.critical('Seat A-1 was taken')

    // The sequence differs, which is what makes the live region fire again. Without it the
    // second occurrence would be silent, and the person would not know it had happened.
    expect(announcements.map((a) => a.sequence)).toEqual([1, 2])
  })

  it('never announces other people\'s seat changes individually', () => {
    const { announcements, listener } = collector()
    let clock = 0
    const announcer = new Announcer(() => clock)
    announcer.subscribe(listener)

    // Fifty changes in half a second, which is an ordinary rate during a sell-out.
    for (let i = 0; i < 50; i += 1) {
      clock += 10
      announcer.ambient(5000 - i)
    }

    // Exactly one: the first. Everything after it is inside the rate limit.
    expect(announcements).toHaveLength(1)
    expect(announcements[0]?.politeness).toBe('polite')
  })

  it('summarises the room again once the interval has passed', () => {
    const { announcements, listener } = collector()
    let clock = 0
    const announcer = new Announcer(() => clock)
    announcer.subscribe(listener)

    announcer.ambient(5000)
    clock += AMBIENT_INTERVAL_MS + 1
    announcer.ambient(4000)

    expect(announcements.map((a) => a.message)).toEqual([
      '5,000 seats still available',
      '4,000 seats still available',
    ])
  })

  it('stays silent when the count has not moved', () => {
    const { announcements, listener } = collector()
    let clock = 0
    const announcer = new Announcer(() => clock)
    announcer.subscribe(listener)

    announcer.ambient(4000)
    clock += AMBIENT_INTERVAL_MS * 3
    const announced = announcer.ambient(4000)

    expect(announced).toBe(false)
    expect(announcements).toHaveLength(1)
  })

  it('says "seat" rather than "seats" when one is left', () => {
    const { announcements, listener } = collector()
    const announcer = new Announcer(() => 0)
    announcer.subscribe(listener)

    announcer.ambient(1)

    expect(announcements[0]?.message).toBe('1 seat still available')
  })

  it('stops delivering after unsubscribe, so a closed page does not keep talking', () => {
    const { announcements, listener } = collector()
    const announcer = new Announcer()
    const unsubscribe = announcer.subscribe(listener)

    announcer.critical('one')
    unsubscribe()
    announcer.critical('two')

    expect(announcements).toHaveLength(1)
  })

  it('does not let an ambient update drown a critical one', () => {
    const { announcements, listener } = collector()
    let clock = 0
    const announcer = new Announcer(() => clock)
    announcer.subscribe(listener)

    announcer.ambient(5000)
    for (let i = 0; i < 20; i += 1) {
      clock += 100
      announcer.ambient(5000 - i)
    }
    announcer.critical('Your hold expired')

    const critical = announcements.filter((a) => a.politeness === 'assertive')
    expect(critical).toHaveLength(1)
    expect(vi.isFakeTimers()).toBe(false)
  })
})
