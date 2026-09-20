import { useEffect, useMemo, useRef, useState } from 'react'
import { formatCountdown, spokenCountdown } from '../lib/money'

export interface HoldTimerProps {
  /** ISO timestamp from the server. */
  expiresAt: string
  /** The server's clock at the moment the hold was reported, used to correct for device skew. */
  serverTime: string
  onExpired: () => void
}

/**
 * The countdown on a hold.
 *
 * <h2>Whose clock</h2>
 *
 * The remaining time is computed against the <em>server's</em> clock, not the device's. Phone
 * clocks are wrong often enough to matter, and a timer that says 1:30 when the server thinks the
 * hold expired ten seconds ago produces a buyer who watched a countdown and still lost their
 * seats. The offset between the two clocks is measured once when the hold is reported and applied
 * from then on.
 *
 * <h2>What a screen reader hears</h2>
 *
 * "2:05" is announced by most screen readers as a time of day. The visible text stays "2:05" and
 * a visually hidden live region carries "2 minutes 5 seconds" instead.
 *
 * <p>The live region is <b>polite</b> and updates only at meaningful thresholds — every thirty
 * seconds, then every ten under a minute, then every second under ten. A live region that updates
 * once a second makes a screen reader unusable: nothing else can be heard over it.
 */
export function HoldTimer({ expiresAt, serverTime, onExpired }: HoldTimerProps) {
  const clockOffsetMs = useMemo(
    () => new Date(serverTime).getTime() - Date.now(),
    [serverTime],
  )

  const expiresAtMs = useMemo(() => new Date(expiresAt).getTime(), [expiresAt])
  const [remaining, setRemaining] = useState(() =>
    Math.max(0, Math.round((expiresAtMs - (Date.now() + clockOffsetMs)) / 1000)),
  )
  const announcedAt = useRef<number | null>(null)
  const firedExpiry = useRef(false)

  useEffect(() => {
    const tick = () => {
      const seconds = Math.max(0, Math.round((expiresAtMs - (Date.now() + clockOffsetMs)) / 1000))
      setRemaining(seconds)
      if (seconds === 0 && !firedExpiry.current) {
        firedExpiry.current = true
        onExpired()
      }
    }
    tick()
    const interval = setInterval(tick, 1_000)
    return () => clearInterval(interval)
  }, [clockOffsetMs, expiresAtMs, onExpired])

  const shouldAnnounce = (seconds: number): boolean => {
    if (seconds <= 10) return true
    if (seconds <= 60) return seconds % 10 === 0
    return seconds % 30 === 0
  }

  const announcement =
    shouldAnnounce(remaining) && announcedAt.current !== remaining
      ? `${spokenCountdown(remaining)} left to complete your purchase`
      : null
  if (announcement) announcedAt.current = remaining

  const urgent = remaining <= 30

  return (
    <div className={`wc-holdtimer${urgent ? ' wc-holdtimer--urgent' : ''}`}>
      <span className="wc-holdtimer__label" id="hold-timer-label">
        Time left to check out
      </span>
      {/* aria-hidden: the same information is in the live region below, spelled out. */}
      <span className="wc-holdtimer__value" aria-hidden="true" data-testid="hold-countdown">
        {formatCountdown(remaining)}
      </span>
      <span className="wc-visually-hidden" role="status" aria-live="polite" aria-atomic="true">
        {announcement ?? ''}
      </span>
    </div>
  )
}
