import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { fetchEvents } from '../lib/reservations'

export function EventsPage() {
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['events'],
    queryFn: fetchEvents,
  })

  return (
    <>
      <h1>On sale now</h1>

      {isPending && <p>Loading events…</p>}

      {isError && (
        <p role="alert" className="wc-message wc-message--error">
          Could not load the events: {error instanceof Error ? error.message : 'unknown error'}
        </p>
      )}

      {data && data.length === 0 && (
        <p>
          Nothing is on sale at the moment. The organizer dashboard can put an event on sale.
        </p>
      )}

      {data && data.length > 0 && (
        <ul className="wc-eventlist">
          {data.map((event) => (
            <li key={event.id} className="wc-card">
              <h2>
                <Link to={`/events/${event.id}`}>{event.name}</Link>
              </h2>
              <dl className="wc-keyvalue">
                <dt>Doors</dt>
                <dd>{new Date(event.startsAt).toLocaleString()}</dd>
                <dt>Capacity</dt>
                <dd>{event.capacity.toLocaleString()} seats</dd>
                <dt>Hold time</dt>
                <dd>{event.holdTtlSeconds} seconds to complete checkout</dd>
                <dt>Maximum per order</dt>
                <dd>{event.maxSeatsPerOrder} seats</dd>
              </dl>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
