import { useEffect, useState } from 'react'
import type { Announcer, Announcement } from '../lib/announcer'

/**
 * The two ARIA live regions everything else announces through.
 *
 * <h2>Why two regions and not one</h2>
 *
 * `aria-live="assertive"` interrupts whatever the screen reader is saying; `polite` waits for a
 * pause. Switching a single region's politeness at runtime is unreliable — several screen readers
 * read the attribute when the region is created and never look again — so there are two regions,
 * each with a fixed politeness, and the announcement is routed to the right one.
 *
 * <h2>Why the message is keyed by sequence</h2>
 *
 * A live region announces when its *content changes*. Setting it to the same string twice is not
 * a change, so the second occurrence is silent — and "seat A-12 was taken" happening twice needs
 * saying twice. Rendering a keyed child forces a real DOM change each time.
 *
 * <h2>Why the regions are always present</h2>
 *
 * A live region added to the DOM at the moment it has something to say is usually missed: the
 * screen reader has to observe the region before the change, not with it. Both regions are
 * rendered empty from first paint and never unmounted.
 */
export function LiveAnnouncer({ announcer }: { announcer: Announcer }) {
  const [assertive, setAssertive] = useState<Announcement | null>(null)
  const [polite, setPolite] = useState<Announcement | null>(null)

  useEffect(() => {
    return announcer.subscribe((announcement) => {
      if (announcement.politeness === 'assertive') setAssertive(announcement)
      else setPolite(announcement)
    })
  }, [announcer])

  return (
    <>
      <div
        className="wc-visually-hidden"
        role="alert"
        aria-live="assertive"
        aria-atomic="true"
        data-testid="announcer-assertive"
      >
        {assertive && <span key={assertive.sequence}>{assertive.message}</span>}
      </div>
      <div
        className="wc-visually-hidden"
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-testid="announcer-polite"
      >
        {polite && <span key={polite.sequence}>{polite.message}</span>}
      </div>
    </>
  )
}
