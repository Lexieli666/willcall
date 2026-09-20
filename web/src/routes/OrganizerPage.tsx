import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useSeatStream } from '../hooks/useSeatStream'
import { fetchEvents } from '../lib/reservations'

/**
 * Live inventory for whoever is running the sale.
 *
 * <p>The numbers come from the same stream the buyers are on, not from a polling loop. An
 * organizer watching a sell-out needs to see the same state at the same moment as the people
 * buying; a dashboard that lags by a poll interval is a dashboard that reports a sell-out after
 * the support queue does.
 */
export function OrganizerPage() {
  const { data: events } = useQuery({ queryKey: ['events'], queryFn: fetchEvents })
  const [selectedEventId, setSelectedEventId] = useState<string | undefined>(undefined)
  const activeEventId = selectedEventId ?? events?.[0]?.id
  const stream = useSeatStream(activeEventId)

  const event = events?.find((candidate) => candidate.id === activeEventId)
  const counts = stream.counts
  const capacity = event?.capacity ?? 0
  const sold = counts?.sold ?? 0
  const sellThrough = capacity > 0 ? Math.round((sold / capacity) * 1000) / 10 : 0

  return (
    <>
      <h1>Organizer dashboard</h1>

      {!events && <p>Loading events…</p>}
      {events && events.length === 0 && <p>No events are on sale.</p>}

      {events && events.length > 0 && (
        <>
          <label className="wc-field">
            Event{' '}
            <select
              value={activeEventId ?? ''}
              onChange={(changeEvent) => setSelectedEventId(changeEvent.target.value)}
            >
              {events.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.name}
                </option>
              ))}
            </select>
          </label>

          <div className="wc-grid-2">
            <section className="wc-card" aria-labelledby="inventory-heading">
              <h2 id="inventory-heading">Live inventory</h2>
              <dl className="wc-keyvalue" aria-live="polite">
                <dt>Capacity</dt>
                <dd>{capacity.toLocaleString()}</dd>
                <dt>Available</dt>
                <dd>{(counts?.available ?? 0).toLocaleString()}</dd>
                <dt>On hold</dt>
                <dd>{(counts?.held ?? 0).toLocaleString()}</dd>
                <dt>Sold</dt>
                <dd>{sold.toLocaleString()}</dd>
                <dt>Sell-through</dt>
                <dd>{sellThrough}%</dd>
              </dl>

              {/* A meter rather than a bare bar: it carries its value to assistive technology
                  without a parallel aria-label that can drift out of step. */}
              <meter
                className="wc-meter"
                min={0}
                max={capacity || 1}
                value={sold}
                aria-label={`Sold ${sold} of ${capacity} seats`}
              >
                {sellThrough}%
              </meter>
            </section>

            <section className="wc-card" aria-labelledby="stream-heading">
              <h2 id="stream-heading">Real-time feed</h2>
              <dl className="wc-keyvalue">
                <dt>Connection</dt>
                <dd>{stream.status}</dd>
                <dt>Sequence</dt>
                <dd>{stream.sequence ?? '—'}</dd>
                <dt>Snapshots received</dt>
                <dd>{stream.stats.snapshots}</dd>
                <dt>Deltas applied</dt>
                <dd>{stream.stats.deltas}</dd>
                <dt>Gaps detected and recovered</dt>
                <dd>{stream.stats.gaps}</dd>
                <dt>Resyncs requested by the server</dt>
                <dd>{stream.stats.resyncs}</dd>
                <dt>Reconnects</dt>
                <dd>{stream.stats.reconnects}</dd>
              </dl>
              <p className="wc-note">
                Gaps are expected under load and are not errors: they are the protocol noticing a
                lost message and recovering. A gap count that never moves during a burst usually
                means the counter is broken, not that nothing was lost.
              </p>
            </section>
          </div>
        </>
      )}
    </>
  )
}
