import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SeatMap } from './SeatMap'
import { SeatMapStore } from '../lib/seatMapStore'
import type { SeatMapResponse } from '../lib/seatTypes'

function buildMap(rows: number, seatsPerRow: number, taken: string[] = []): SeatMapResponse {
  let n = 0
  return {
    eventId: 'event-1',
    sequence: 0,
    availableCount: rows * seatsPerRow - taken.length,
    heldCount: taken.length,
    soldCount: 0,
    sections: [
      {
        id: 'section-1',
        name: 'Floor',
        displayOrder: 0,
        rows: Array.from({ length: rows }, (_, rowIndex) => ({
          id: `row-${rowIndex}`,
          label: String.fromCharCode(65 + rowIndex),
          displayOrder: rowIndex,
          seats: Array.from({ length: seatsPerRow }, (_, seatIndex) => {
            const id = `seat-${n++}`
            return {
              id,
              rowId: `row-${rowIndex}`,
              number: seatIndex + 1,
              label: `${String.fromCharCode(65 + rowIndex)}-${seatIndex + 1}`,
              status: (taken.includes(id) ? 'SOLD' : 'AVAILABLE'),
              version: 0,
              priceCents: 4500,
            }
          }),
        })),
      },
    ],
  }
}

function renderMap(store: SeatMapStore, selected = new Set<string>()) {
  const onSelect = vi.fn()
  const view = render(
    <SeatMap store={store} revision={0} selectedIds={selected} onSelect={onSelect} />,
  )
  return { onSelect, view }
}

describe('SeatMap keyboard navigation', () => {
  it('is a single tab stop, not one per seat', () => {
    // Five thousand tab stops would make the map impossible to get past. Exactly one seat carries
    // tabIndex 0; every other seat is -1 and reached with the arrow keys.
    const store = new SeatMapStore(buildMap(3, 4))
    renderMap(store)

    const focusable = screen.getAllByRole('gridcell').filter((cell) => cell.tabIndex === 0)
    expect(focusable).toHaveLength(1)
    expect(focusable[0]).toHaveAttribute('data-seat-id', 'seat-0')
  })

  it('moves right and left within a row and stops at the ends', async () => {
    const user = userEvent.setup()
    const store = new SeatMapStore(buildMap(2, 3))
    renderMap(store)

    await user.tab()
    expect(screen.getByLabelText(/Seat A-1/)).toHaveFocus()

    await user.keyboard('{ArrowRight}{ArrowRight}')
    expect(screen.getByLabelText(/Seat A-3/)).toHaveFocus()

    // At the end of the row it stays put rather than wrapping into the next row, which would
    // silently move the buyer to a different part of the venue.
    await user.keyboard('{ArrowRight}')
    expect(screen.getByLabelText(/Seat A-3/)).toHaveFocus()

    await user.keyboard('{ArrowLeft}{ArrowLeft}{ArrowLeft}')
    expect(screen.getByLabelText(/Seat A-1/)).toHaveFocus()
  })

  it('moves between rows keeping the seat position', async () => {
    const user = userEvent.setup()
    const store = new SeatMapStore(buildMap(3, 4))
    renderMap(store)

    await user.tab()
    await user.keyboard('{ArrowRight}{ArrowRight}')
    expect(screen.getByLabelText(/Seat A-3/)).toHaveFocus()

    await user.keyboard('{ArrowDown}')
    expect(screen.getByLabelText(/Seat B-3/)).toHaveFocus()

    await user.keyboard('{ArrowUp}')
    expect(screen.getByLabelText(/Seat A-3/)).toHaveFocus()
  })

  it('clamps to the shorter row when moving into one', async () => {
    const user = userEvent.setup()
    const map = buildMap(2, 5)
    // Second row is shorter, as a real venue's often is.
    map.sections[0]!.rows[1]!.seats = map.sections[0]!.rows[1]!.seats.slice(0, 2)
    const store = new SeatMapStore(map)
    renderMap(store)

    await user.tab()
    await user.keyboard('{ArrowRight}{ArrowRight}{ArrowRight}{ArrowRight}')
    expect(screen.getByLabelText(/Seat A-5/)).toHaveFocus()

    await user.keyboard('{ArrowDown}')
    expect(screen.getByLabelText(/Seat B-2/)).toHaveFocus()
  })

  it('jumps to the ends of a row with Home and End, and of the map with Ctrl', async () => {
    const user = userEvent.setup()
    const store = new SeatMapStore(buildMap(4, 5))
    renderMap(store)

    await user.tab()
    await user.keyboard('{End}')
    expect(screen.getByLabelText(/Seat A-5/)).toHaveFocus()

    await user.keyboard('{Home}')
    expect(screen.getByLabelText(/Seat A-1/)).toHaveFocus()

    await user.keyboard('{Control>}{End}{/Control}')
    expect(screen.getByLabelText(/Seat D-5/)).toHaveFocus()

    await user.keyboard('{Control>}{Home}{/Control}')
    expect(screen.getByLabelText(/Seat A-1/)).toHaveFocus()
  })

  it('moves ten rows at a time with PageDown and PageUp', async () => {
    const user = userEvent.setup()
    const store = new SeatMapStore(buildMap(25, 3))
    renderMap(store)

    await user.tab()
    await user.keyboard('{PageDown}')
    expect(screen.getByLabelText(/Seat K-1/)).toHaveFocus()

    await user.keyboard('{PageUp}')
    expect(screen.getByLabelText(/Seat A-1/)).toHaveFocus()
  })

  it('selects with Enter and with Space', async () => {
    const user = userEvent.setup()
    const store = new SeatMapStore(buildMap(2, 3))
    const { onSelect } = renderMap(store)

    await user.tab()
    await user.keyboard('{Enter}')
    expect(onSelect).toHaveBeenCalledWith('seat-0')

    await user.keyboard('{ArrowRight} ')
    expect(onSelect).toHaveBeenCalledWith('seat-1')
  })
})

describe('SeatMap semantics', () => {
  it('names every seat with its state in words, not only in colour', () => {
    const store = new SeatMapStore(buildMap(1, 3, ['seat-1']))
    renderMap(store)

    expect(screen.getByLabelText('Seat A-1, $45.00, available')).toBeInTheDocument()
    expect(screen.getByLabelText('Seat A-2, sold')).toBeInTheDocument()
  })

  it('says a seat is selected in its accessible name', () => {
    const store = new SeatMapStore(buildMap(1, 2))
    renderMap(store, new Set(['seat-0']))

    expect(screen.getByLabelText('Seat A-1, $45.00, available, selected')).toBeInTheDocument()
  })

  it('marks an unavailable seat as disabled rather than merely colouring it', () => {
    const store = new SeatMapStore(buildMap(1, 2, ['seat-1']))
    renderMap(store)

    expect(screen.getByLabelText(/Seat A-2/)).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByLabelText(/Seat A-1/)).not.toHaveAttribute('aria-disabled')
  })

  it('does not select an unavailable seat', async () => {
    const user = userEvent.setup()
    const store = new SeatMapStore(buildMap(1, 2, ['seat-1']))
    const { onSelect } = renderMap(store)

    await user.click(screen.getByLabelText(/Seat A-2/))

    expect(onSelect).not.toHaveBeenCalled()
  })

  it('carries a glyph as well as a colour for every state', () => {
    const store = new SeatMapStore(buildMap(1, 2, ['seat-1']))
    renderMap(store)

    // The glyph is what survives a colour-vision deficiency, a greyscale print, and Windows
    // high-contrast mode, where every author colour is replaced.
    expect(screen.getByLabelText(/Seat A-1/).textContent).toBe('○')
    expect(screen.getByLabelText(/Seat A-2/).textContent).toBe('●')
  })

  it('exposes one grid per section, labelled by that section', () => {
    const store = new SeatMapStore(buildMap(2, 2))
    renderMap(store)

    expect(screen.getByRole('grid', { name: 'Floor' })).toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(2)
    expect(screen.getAllByRole('rowheader').map((header) => header.textContent)).toEqual(['A', 'B'])
  })
})
