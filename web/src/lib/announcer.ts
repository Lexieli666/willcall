/**
 * Throttled announcements for a screen reader.
 *
 * <h2>Why this is not just an aria-live region</h2>
 *
 * During a flash sale the seat map changes dozens of times a second. Wiring that to a live region
 * directly makes a screen reader unusable: it reads every change, queues the ones it cannot keep
 * up with, and the person hears a continuous stream of seat numbers with no way to interrupt it.
 * The result is worse than no announcements at all, because it also drowns out the messages that
 * matter — "your hold expired", "that seat is gone".
 *
 * So announcements are classified and rationed:
 *
 * - **Critical** — the buyer's own state changed: a hold acquired, a hold about to expire, a seat
 *   they had selected taken by somebody else. Announced immediately and assertively. These are
 *   rare by construction: they concern one person's own actions.
 * - **Ambient** — the room changed: other people's seats. Never announced individually. Summarised
 *   at most once every {@link AMBIENT_INTERVAL_MS} as a count, and only when the count has
 *   actually moved.
 *
 * The distinction is what makes the map usable without sight: a person hears what happened to
 * *them* at once, and what happened to *the venue* at a pace a human can absorb.
 */
export const AMBIENT_INTERVAL_MS = 10_000

export type Politeness = 'assertive' | 'polite'

export interface Announcement {
  message: string
  politeness: Politeness
  /** Increments on each new announcement so an identical message still re-announces. */
  sequence: number
}

export class Announcer {
  private sequence = 0
  // Negative infinity, not zero. With zero, the first ambient update is inside the rate limit
  // whenever the clock starts near it — which in a test it always does, and in a browser it does
  // for the first ten seconds after load. That is exactly when somebody most wants to know how
  // many seats are left.
  private lastAmbientAt = Number.NEGATIVE_INFINITY
  private lastAmbientCount: number | null = null
  private readonly listeners = new Set<(announcement: Announcement) => void>()

  constructor(private readonly now: () => number = () => Date.now()) {}

  subscribe(listener: (announcement: Announcement) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** The buyer's own state changed. Always announced, immediately. */
  critical(message: string): void {
    this.emit(message, 'assertive')
  }

  /** Something worth saying but not worth interrupting for. */
  polite(message: string): void {
    this.emit(message, 'polite')
  }

  /**
   * The room changed.
   *
   * <p>Rate limited, and suppressed entirely when the number has not moved since the last time.
   * Saying "1,412 seats available" twice in a row is noise; saying it every fifty milliseconds is
   * an unusable page.
   *
   * @returns whether anything was announced, which the tests assert on
   */
  ambient(availableSeats: number): boolean {
    const now = this.now()
    if (availableSeats === this.lastAmbientCount) return false
    if (now - this.lastAmbientAt < AMBIENT_INTERVAL_MS) return false

    this.lastAmbientAt = now
    this.lastAmbientCount = availableSeats
    this.emit(
      `${availableSeats.toLocaleString()} seat${availableSeats === 1 ? '' : 's'} still available`,
      'polite',
    )
    return true
  }

  private emit(message: string, politeness: Politeness): void {
    this.sequence += 1
    const announcement = { message, politeness, sequence: this.sequence }
    for (const listener of this.listeners) listener(announcement)
  }
}
