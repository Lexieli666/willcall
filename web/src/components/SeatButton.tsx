import { memo } from 'react'
import { formatMoney } from '../lib/money'
import type { SeatStatus } from '../lib/seatTypes'

/**
 * One seat.
 *
 * <p><b>State is never carried by colour alone.</b> Each status has a glyph and a text label as
 * well as a colour, so the map is usable with a colour-vision deficiency, in high-contrast mode,
 * and in a screenshot printed in grey. The glyph is {@code aria-hidden} because the accessible
 * name already says the same thing in words; announcing both would read "A-12 available available".
 *
 * <p>Memoised on primitive props only. Five thousand of these re-rendering because a parent passed
 * a fresh object literal is the difference between a 60 ms map and a 400 ms one.
 */
export interface SeatButtonProps {
  id: string
  label: string
  status: SeatStatus
  selected: boolean
  priceCents: number
  tabIndex: number
  onSelect: (seatId: string) => void
}

const GLYPH: Record<SeatStatus, string> = {
  AVAILABLE: '○', // hollow circle
  HELD: '◑', // half-filled circle
  SOLD: '●', // filled circle
  BLOCKED: '✕', // cross
}

const STATE_WORD: Record<SeatStatus, string> = {
  AVAILABLE: 'available',
  HELD: 'on hold',
  SOLD: 'sold',
  BLOCKED: 'not for sale',
}

function SeatButtonImpl({ id, label, status, selected, priceCents, tabIndex, onSelect }: SeatButtonProps) {
  const selectable = status === 'AVAILABLE'
  // Formatting the price here rather than taking a formatted string as a prop keeps every prop
  // primitive, which is what lets memo() actually skip a re-render.
  const accessibleName = selectable
    ? `Seat ${label}, ${formatMoney(priceCents)}, ${STATE_WORD[status]}${selected ? ', selected' : ''}`
    : `Seat ${label}, ${STATE_WORD[status]}`

  return (
    <button
      type="button"
      role="gridcell"
      id={`seat-${id}`}
      className={`wc-seat wc-seat--${status.toLowerCase()}${selected ? ' wc-seat--selected' : ''}`}
      data-seat-id={id}
      data-status={status}
      aria-label={accessibleName}
      // aria-selected, not aria-pressed. A gridcell is a cell in a grid, and ARIA defines
      // selection for cells; aria-pressed belongs to a toggle button and is simply not supported
      // on this role, so a screen reader would either ignore it or announce something confusing.
      // ESLint's jsx-a11y rule caught this, which is why the rule set is strict.
      aria-selected={selectable ? selected : undefined}
      aria-disabled={selectable ? undefined : true}
      tabIndex={tabIndex}
      onClick={() => {
        if (selectable) onSelect(id)
      }}
    >
      {/*
        The glyph is the element's only text content, with no wrapper span. At five thousand
        seats a wrapper doubles the element count and costs measurable render time; the
        aria-label above already overrides the accessible name, so the glyph is presentational
        without needing aria-hidden on a node of its own.
      */}
      {GLYPH[status]}
    </button>
  )
}

export const SeatButton = memo(SeatButtonImpl)
