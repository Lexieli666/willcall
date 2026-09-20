import { memo } from 'react'
import { SeatButton } from './SeatButton'
import type { StoreRow } from '../lib/seatMapStore'

export interface SeatRowProps {
  row: StoreRow
  /**
   * The row's revision counter, passed separately so React's shallow prop comparison sees it.
   * The row object and its arrays are mutated in place and keep their identity; this number is
   * what says "something inside changed".
   */
  revision: number
  selectedIds: ReadonlySet<string>
  /** Index of the seat in this row that is the tab stop, or -1 when the row has none. */
  rovingIndex: number
  onSelect: (seatId: string) => void
}

/**
 * One row of the grid.
 *
 * <p>Memoised so a delta touching two seats repaints two rows rather than the whole venue.
 * `content-visibility: auto` in the stylesheet lets the browser skip layout and paint for rows
 * scrolled out of view — most of them, on a phone — while keeping them in the accessibility tree
 * and reachable by keyboard. Skipping rendering is not the same as removing content.
 */
function SeatRowImpl({ row, selectedIds, rovingIndex, onSelect }: SeatRowProps) {
  return (
    <div className="wc-seatrow" role="row" data-row-id={row.id}>
      <span className="wc-seatrow__label" role="rowheader">
        {row.label}
      </span>
      {row.seats.map((seat, index) => (
        <SeatButton
          key={seat.id}
          id={seat.id}
          label={seat.label}
          status={row.statuses[index] ?? seat.status}
          selected={selectedIds.has(seat.id)}
          priceCents={seat.priceCents}
          tabIndex={index === rovingIndex ? 0 : -1}
          onSelect={onSelect}
        />
      ))}
    </div>
  )
}

export const SeatRow = memo(SeatRowImpl)
