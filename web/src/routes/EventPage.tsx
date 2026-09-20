import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { SeatMap } from '../components/SeatMap'
import { SeatListMode } from '../components/SeatListMode'
import { HoldTimer } from '../components/HoldTimer'
import { LiveAnnouncer } from '../components/LiveAnnouncer'
import { Announcer } from '../lib/announcer'
import { useSeatStream } from '../hooks/useSeatStream'
import { ApiError } from '../lib/api'
import {
  cancelHold,
  confirmOrder,
  createHold,
  fetchBuyerState,
  fetchEvent,
  newIdempotencyKey,
} from '../lib/reservations'
import { formatMoney } from '../lib/money'
import type { HoldResponse } from '../lib/seatTypes'

type ViewMode = 'map' | 'list'

/**
 * The purchase page: seat map, selection, hold, countdown, checkout.
 *
 * <h2>What a buyer sees when they lose a seat</h2>
 *
 * Two distinct cases, handled differently on purpose (see ADR 0011):
 *
 * <ul>
 *   <li><b>The seat was taken between render and click.</b> The map updates that seat in place,
 *       the selection drops it, and the message names the seat. The buyer is never silently given
 *       a different seat than the one they chose.
 *   <li><b>The hold expired during checkout.</b> The form is disabled, the reason is announced as
 *       well as shown, and the same seats are offered again if they are still free.
 * </ul>
 */
export function EventPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const navigate = useNavigate()
  const stream = useSeatStream(eventId)
  // One announcer for the life of the page: recreating it would reset the ambient rate limit on
  // every render, which is the same as having no throttle at all.
  const announcer = useMemo(() => new Announcer(), [])
  const panelHeadingRef = useRef<HTMLHeadingElement>(null)

  const [viewMode, setViewMode] = useState<ViewMode>('map')
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [hold, setHold] = useState<HoldResponse | null>(null)
  const [holdKey, setHoldKey] = useState(() => newIdempotencyKey())
  const [confirmKey, setConfirmKey] = useState(() => newIdempotencyKey())
  const [message, setMessage] = useState<{ tone: 'error' | 'success' | 'info'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [renderMs, setRenderMs] = useState<number | null>(null)

  const { data: eventData } = useQuery({
    queryKey: ['event', eventId],
    queryFn: () => fetchEvent(eventId as string),
    enabled: Boolean(eventId),
  })
  const event = eventData?.event

  const { data: buyerState, refetch: refetchBuyerState } = useQuery({
    queryKey: ['buyer-state', eventId],
    queryFn: () => fetchBuyerState(eventId as string),
    enabled: Boolean(eventId),
  })

  // Adopt an existing hold on reload, so refreshing the page does not orphan seats the buyer
  // still holds and is still paying time for.
  //
  // The expiry guard is not defensive padding. Without it, a cached buyer-state response taken
  // before the hold expired would be re-adopted the moment the timer cleared it, and the Pay
  // button would reappear for seats that were already back on sale. An end-to-end test caught
  // exactly that: the page announced "your hold expired" and kept offering checkout.
  useEffect(() => {
    if (hold || !buyerState) return
    const serverNow = new Date(buyerState.serverTime).getTime()
    const existing = buyerState.holds.find(
      (candidate) => candidate.status === 'ACTIVE' && new Date(candidate.expiresAt).getTime() > serverNow,
    )
    if (!existing) return
    setHold({
      holdId: existing.holdId,
      eventId: buyerState.eventId,
      seatIds: existing.seatIds,
      expiresAt: existing.expiresAt,
      secondsRemaining: existing.secondsRemaining,
      status: existing.status,
    })
    setSelected(new Set(existing.seatIds))
  }, [buyerState, hold])

  // A seat in the selection that someone else took must leave the selection, and the buyer must
  // be told which one. Silently dropping it produces a "why did my total change" support ticket.
  useEffect(() => {
    if (!stream.store || selected.size === 0 || hold) return
    const lost: string[] = []
    for (const seatId of selected) {
      if (stream.store.statusOf(seatId) !== 'AVAILABLE') lost.push(seatId)
    }
    if (lost.length === 0) return

    const store = stream.store
    const labels = lost
      .map((id) => store.rows.flatMap((row) => row.seats).find((seat) => seat.id === id)?.label ?? id)
      .join(', ')
    setSelected((current) => new Set([...current].filter((id) => !lost.includes(id))))
    const text = `Someone else took ${labels}. Those seats are no longer selected.`
    setMessage({ tone: 'error', text })
    // Critical: this is the buyer's own selection changing under them, not the room moving.
    announcer.critical(text)
  }, [stream.revision, stream.store, selected, hold, announcer])

  // The room, summarised at a pace a person can absorb. Individual seat changes are never
  // announced: during a sell-out there are dozens a second, and reading them all would drown out
  // everything that matters.
  useEffect(() => {
    if (stream.counts) announcer.ambient(stream.counts.available)
  }, [stream.counts, announcer])

  const selectedSeats = useMemo(() => {
    if (!stream.store) return []
    return stream.store.rows
      .flatMap((row) => row.seats.map((seat, index) => ({ seat, status: row.statuses[index], row })))
      .filter((entry) => selected.has(entry.seat.id))
    // The store's arrays are mutated in place, so `revision` is the only signal that their
    // contents changed. Listing it is the point, not an oversight.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stream.store, stream.revision, selected])

  const totalCents = selectedSeats.reduce((sum, entry) => sum + entry.seat.priceCents, 0)
  const maxSeats = event?.maxSeatsPerOrder ?? 8

  const toggleSeat = useCallback(
    (seatId: string) => {
      if (hold) return
      setMessage(null)
      setSelected((current) => {
        const next = new Set(current)
        if (next.has(seatId)) next.delete(seatId)
        else if (next.size >= maxSeats) {
          setMessage({ tone: 'error', text: `This event allows at most ${maxSeats} seats in one order.` })
          return current
        } else next.add(seatId)
        return next
      })
    },
    [hold, maxSeats],
  )

  /** Announced and focused together, because both hold paths change the same part of the page. */
  const announceHeld = (seatCount: number, secondsRemaining: number): void => {
    announcer.critical(
      `${seatCount} seat${seatCount === 1 ? '' : 's'} held. ` +
        `You have ${secondsRemaining} seconds to complete your purchase.`,
    )
    panelHeadingRef.current?.focus()
  }

  const describeError = (cause: unknown): string => {
    if (!(cause instanceof ApiError)) return cause instanceof Error ? cause.message : 'Something went wrong.'
    switch (cause.code) {
      case 'seat_unavailable':
        return 'One of those seats was taken while you were choosing. The map has been updated.'
      case 'sold_out':
        return 'This event is sold out.'
      case 'not_enough_contiguous_seats':
        return 'There is no block of that many seats together. Try fewer, or pick seats apart.'
      case 'hold_expired':
      case 'hold_not_active':
        return 'Your hold expired and the seats went back on sale.'
      case 'payment_declined':
        return 'The payment was declined and the seats went back on sale.'
      case 'payment_timeout':
        return 'The payment gateway did not answer. Your seats are still held — try again.'
      case 'rate_limited':
        return `You are going too fast. Try again in ${cause.retryAfterSeconds ?? 1} seconds.`
      case 'too_many_seats':
        return `This event allows at most ${maxSeats} seats in one order.`
      default:
        return cause.message
    }
  }

  const holdSelected = async () => {
    if (!eventId || selected.size === 0) return
    setBusy(true)
    setMessage(null)
    try {
      const response = await createHold(eventId, { seatIds: [...selected] }, holdKey)
      setHold(response)
      setMessage({
        tone: 'success',
        text: `${response.seatIds.length} seat(s) held. Complete checkout before the timer runs out.`,
      })
      announceHeld(response.seatIds.length, response.secondsRemaining)
      void refetchBuyerState()
    } catch (cause) {
      setMessage({ tone: 'error', text: describeError(cause) })
      // A new key for the next attempt: the previous request is settled, and reusing its key
      // would make the retry a replay of a request the buyer has since changed.
      setHoldKey(newIdempotencyKey())
    } finally {
      setBusy(false)
    }
  }

  const holdBestAvailable = async (quantity: number, together: boolean) => {
    if (!eventId) return
    setBusy(true)
    setMessage(null)
    try {
      const response = await createHold(eventId, { quantity, together }, holdKey)
      setHold(response)
      setSelected(new Set(response.seatIds))
      setMessage({ tone: 'success', text: `${response.seatIds.length} seat(s) held.` })
      announceHeld(response.seatIds.length, response.secondsRemaining)
      void refetchBuyerState()
    } catch (cause) {
      setMessage({ tone: 'error', text: describeError(cause) })
      setHoldKey(newIdempotencyKey())
    } finally {
      setBusy(false)
    }
  }

  const releaseHold = async () => {
    if (!hold) return
    setBusy(true)
    try {
      await cancelHold(hold.holdId)
      setHold(null)
      setSelected(new Set())
      setHoldKey(newIdempotencyKey())
      const text = 'Your hold was released and the seats are back on sale.'
      setMessage({ tone: 'info', text })
      announcer.polite(text)
      panelHeadingRef.current?.focus()
      void refetchBuyerState()
    } catch (cause) {
      setMessage({ tone: 'error', text: describeError(cause) })
    } finally {
      setBusy(false)
    }
  }

  const checkout = async () => {
    if (!hold) return
    setBusy(true)
    setMessage(null)
    try {
      const order = await confirmOrder(hold.holdId, confirmKey)
      void navigate(`/orders/${order.orderId}`)
    } catch (cause) {
      const text = describeError(cause)
      setMessage({ tone: 'error', text })
      announcer.critical(text)
      if (cause instanceof ApiError && cause.code === 'payment_timeout') {
        // Deliberately keep the same key: the charge may have landed, and only a retry carrying
        // this key can complete the order rather than creating a second one.
      } else {
        setConfirmKey(newIdempotencyKey())
      }
      if (cause instanceof ApiError && (cause.code === 'hold_expired' || cause.code === 'hold_not_active')) {
        setHold(null)
        setSelected(new Set())
      }
    } finally {
      setBusy(false)
    }
  }

  const onHoldExpired = useCallback(() => {
    setHold(null)
    setSelected(new Set())
    setHoldKey(newIdempotencyKey())
    setConfirmKey(newIdempotencyKey())
    const text =
      'Your hold expired and the seats went back on sale. Choose again if they are still free.'
    setMessage({ tone: 'error', text })
    // Assertive: somebody filling in a payment form needs to know the form is now pointless, and
    // needs to know before they finish typing a card number.
    announcer.critical(text)
    panelHeadingRef.current?.focus()
    // Refresh the buyer state as well, so the cached copy that still contains this hold cannot
    // be adopted again on the next render.
    void refetchBuyerState()
  }, [refetchBuyerState, announcer])

  if (!eventId) return <p role="alert">No event was requested.</p>

  return (
    <>
      <LiveAnnouncer announcer={announcer} />
      <h1>{event?.name ?? 'Loading…'}</h1>

      <div className="wc-eventbar">
        <p className="wc-eventbar__counts" role="status" aria-live="polite">
          {stream.counts
            ? `${stream.counts.available.toLocaleString()} available, ${stream.counts.held.toLocaleString()} on hold, ${stream.counts.sold.toLocaleString()} sold`
            : 'Loading seat availability…'}
        </p>
        <p className={`wc-streamstatus wc-streamstatus--${stream.status}`} data-testid="stream-status">
          Live updates: <strong>{stream.status}</strong>
          <span className="wc-visually-hidden">
            {' '}
            sequence {stream.sequence ?? 'unknown'}, {stream.stats.deltas} updates applied,{' '}
            {stream.stats.gaps} gaps recovered
          </span>
        </p>
        <div className="wc-viewtoggle" role="group" aria-label="Seat map view">
          <button
            type="button"
            aria-pressed={viewMode === 'map'}
            onClick={() => setViewMode('map')}
          >
            Seat map
          </button>
          <button
            type="button"
            aria-pressed={viewMode === 'list'}
            onClick={() => setViewMode('list')}
          >
            Text list
          </button>
        </div>
      </div>

      {message && (
        <p
          className={`wc-message wc-message--${message.tone}`}
          role={message.tone === 'error' ? 'alert' : 'status'}
          aria-live={message.tone === 'error' ? 'assertive' : 'polite'}
        >
          {message.text}
        </p>
      )}

      <div className="wc-eventlayout">
        <div className="wc-eventlayout__map">
          {stream.error && <p role="alert">{stream.error}</p>}
          {!stream.store && !stream.error && <p>Loading the seat map…</p>}

          {stream.store && viewMode === 'map' && (
            <SeatMap
              store={stream.store}
              revision={stream.revision}
              selectedIds={selected}
              onSelect={toggleSeat}
              onFirstRenderMeasured={setRenderMs}
            />
          )}

          {stream.store && viewMode === 'list' && (
            <SeatListMode
              store={stream.store}
              revision={stream.revision}
              selectedIds={selected}
              onSelect={toggleSeat}
            />
          )}

          {stream.store && (
            <p className="wc-seatmap__legend">
              <span className="wc-visually-hidden">Legend: </span>
              <span>○ available</span> <span>◑ on hold</span> <span>● sold</span>{' '}
              <span>✕ not for sale</span>
            </p>
          )}

          {renderMs !== null && (
            <p className="wc-measure" data-testid="render-ms">
              Seat map: {stream.store?.size.toLocaleString()} seats rendered in {renderMs.toFixed(1)} ms
            </p>
          )}
        </div>

        <aside className="wc-eventlayout__panel" aria-label="Your selection">
          {/*
            tabIndex -1 makes this heading programmatically focusable without adding a tab stop.
            Focus moves here after every state change that replaces the panel's contents, so a
            keyboard or screen-reader user lands on what changed rather than being left on a button
            that no longer exists.
          */}
          <h2 ref={panelHeadingRef} tabIndex={-1}>
            Your selection
          </h2>

          {!hold && (
            <>
              <p>
                {selected.size === 0
                  ? 'No seats selected. Choose seats on the map, or ask for the best available.'
                  : `${selected.size} seat(s) selected — ${formatMoney(totalCents)}`}
              </p>

              <ul className="wc-selectionlist">
                {selectedSeats.map((entry) => (
                  <li key={entry.seat.id}>
                    {entry.row.sectionName}, row {entry.row.label}, seat {entry.seat.number} —{' '}
                    {formatMoney(entry.seat.priceCents)}
                  </li>
                ))}
              </ul>

              <button
                type="button"
                className="wc-button wc-button--primary"
                disabled={selected.size === 0 || busy}
                onClick={() => void holdSelected()}
                data-testid="hold-selected"
              >
                Hold these seats
              </button>

              <fieldset className="wc-bestavailable">
                <legend>Or let us choose</legend>
                {[2, 3, 4].map((quantity) => (
                  <button
                    key={quantity}
                    type="button"
                    className="wc-button"
                    disabled={busy}
                    onClick={() => void holdBestAvailable(quantity, true)}
                  >
                    {quantity} together
                  </button>
                ))}
                <button
                  type="button"
                  className="wc-button"
                  disabled={busy}
                  onClick={() => void holdBestAvailable(1, false)}
                  data-testid="best-available-1"
                >
                  1 anywhere
                </button>
              </fieldset>
            </>
          )}

          {hold && (
            <>
              <HoldTimer
                expiresAt={hold.expiresAt}
                serverTime={buyerState?.serverTime ?? new Date().toISOString()}
                onExpired={onHoldExpired}
              />

              <p>
                {hold.seatIds.length} seat(s) held — {formatMoney(totalCents)}
              </p>

              <button
                type="button"
                className="wc-button wc-button--primary"
                disabled={busy}
                onClick={() => void checkout()}
                data-testid="checkout"
              >
                Pay {formatMoney(totalCents)}
              </button>

              <button type="button" className="wc-button" disabled={busy} onClick={() => void releaseHold()}>
                Release these seats
              </button>

              <p className="wc-note">
                No real payment is taken. Checkout is idempotent: if the gateway times out, press
                Pay again — it completes the original charge rather than making a second one.
              </p>
            </>
          )}
        </aside>
      </div>
    </>
  )
}
