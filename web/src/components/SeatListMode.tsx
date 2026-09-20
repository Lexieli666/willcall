import { useMemo, useState } from 'react'
import { formatMoney } from '../lib/money'
import type { SeatMapStore } from '../lib/seatMapStore'
import type { SeatStatus } from '../lib/seatTypes'

export interface SeatListModeProps {
  store: SeatMapStore
  revision: number
  selectedIds: ReadonlySet<string>
  onSelect: (seatId: string) => void
}

const STATE_WORD: Record<SeatStatus, string> = {
  AVAILABLE: 'available',
  HELD: 'on hold',
  SOLD: 'sold',
  BLOCKED: 'not for sale',
}

/**
 * The same inventory as a list of choices rather than a two-dimensional map.
 *
 * <p>This is not a fallback for a broken grid; it is a better interface for some people. A
 * screen-reader user looking for "two seats together under $60" is served far better by a filtered
 * list than by arrowing around a grid counting gaps. The grid is the better interface for someone
 * who cares where in the venue they sit. Both are first-class, and the toggle is a preference,
 * not an accessibility mode.
 *
 * <p>Only available seats are listed by default: a list of five thousand entries, of which four
 * thousand cannot be chosen, is not more accessible than a grid.
 */
export function SeatListMode({ store, revision, selectedIds, onSelect }: SeatListModeProps) {
  const [showUnavailable, setShowUnavailable] = useState(false)
  const [maxPriceCents, setMaxPriceCents] = useState<number | null>(null)

  const entries = useMemo(() => {
    const all = store.allSeats()
    return all.filter(
      (entry) =>
        (showUnavailable || entry.status === 'AVAILABLE') &&
        (maxPriceCents === null || entry.seat.priceCents <= maxPriceCents),
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps -- revision is the change signal
  }, [store, revision, showUnavailable, maxPriceCents])

  const priceOptions = useMemo(() => {
    const prices = new Set(store.allSeats().map((entry) => entry.seat.priceCents))
    return [...prices].sort((a, b) => a - b)
  }, [store])

  return (
    <div className="wc-seatlist">
      <fieldset className="wc-seatlist__filters">
        <legend>Filter seats</legend>

        <label>
          <input
            type="checkbox"
            checked={showUnavailable}
            onChange={(event) => setShowUnavailable(event.target.checked)}
          />{' '}
          Include seats that are not for sale
        </label>

        <label>
          Maximum price{' '}
          <select
            value={maxPriceCents ?? ''}
            onChange={(event) =>
              setMaxPriceCents(event.target.value === '' ? null : Number(event.target.value))
            }
          >
            <option value="">Any</option>
            {priceOptions.map((price) => (
              <option key={price} value={price}>
                {formatMoney(price)} or less
              </option>
            ))}
          </select>
        </label>
      </fieldset>

      <p role="status" aria-live="polite">
        {entries.length} seat{entries.length === 1 ? '' : 's'} match
      </p>

      <ul className="wc-seatlist__items">
        {entries.slice(0, 500).map((entry) => (
          <li key={entry.seat.id}>
            <button
              type="button"
              className="wc-seatlist__seat"
              data-seat-id={entry.seat.id}
              aria-pressed={entry.status === 'AVAILABLE' ? selectedIds.has(entry.seat.id) : undefined}
              aria-disabled={entry.status === 'AVAILABLE' ? undefined : true}
              onClick={() => {
                if (entry.status === 'AVAILABLE') onSelect(entry.seat.id)
              }}
            >
              {entry.row.sectionName}, row {entry.row.label}, seat {entry.seat.number} —{' '}
              {formatMoney(entry.seat.priceCents)} — {STATE_WORD[entry.status]}
              {selectedIds.has(entry.seat.id) ? ' — selected' : ''}
            </button>
          </li>
        ))}
      </ul>

      {entries.length > 500 && (
        <p>
          Showing the first 500 of {entries.length} matching seats. Narrow the filters to see more
          specific choices.
        </p>
      )}
    </div>
  )
}
