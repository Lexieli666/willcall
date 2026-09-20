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

      {/*
        Skeleton cards, not a "Loading…" line.

        The first version rendered one short paragraph and then replaced it with a list of cards,
        which pushed everything below it down the page. Lighthouse measured a cumulative layout
        shift of 0.88 against a 0.05 budget — on a phone that is the page jumping under a thumb
        that is already moving toward a link. The skeletons occupy the same space the real cards
        will, so nothing moves when the data arrives.
      */}
      {isPending && (
        <ul className="wc-eventlist" aria-busy="true" aria-label="Loading events">
          {[0, 1, 2].map((index) => (
            /*
              The skeleton is the *same markup* as a real card, with placeholder text hidden
              behind a block colour. Hand-tuned skeleton heights get close and then drift the
              moment the card gains a line; reusing the structure makes the heights identical by
              construction, which is the only way the shift is actually zero rather than small.
            */
            <li key={index} className="wc-card wc-skeleton" aria-hidden="true">
              <h2>
                <span>Loading event name</span>
              </h2>
              <dl className="wc-keyvalue">
                <dt>
                  <span>Doors</span>
                </dt>
                <dd>
                  <span>00/00/0000, 00:00:00</span>
                </dd>
                <dt>
                  <span>Capacity</span>
                </dt>
                <dd>
                  <span>0,000 seats</span>
                </dd>
                <dt>
                  <span>Hold time</span>
                </dt>
                <dd>
                  <span>000 seconds to complete checkout</span>
                </dd>
                <dt>
                  <span>Maximum per order</span>
                </dt>
                <dd>
                  <span>0 seats</span>
                </dd>
              </dl>
            </li>
          ))}
          <li className="wc-visually-hidden" role="status">
            Loading events
          </li>
        </ul>
      )}

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
