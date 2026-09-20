import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SeatListMode } from './SeatListMode'
import { SeatMapStore } from '../lib/seatMapStore'
import type { SeatMapResponse, SeatStatus } from '../lib/seatTypes'

function buildMap(rows: number, seatsPerRow: number, taken: string[] = [], prices: number[] = [4500]): SeatMapResponse {
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
            const id = `seat-${n}`
            const price = prices[n % prices.length] ?? 4500
            n += 1
            return {
              id,
              rowId: `row-${rowIndex}`,
              number: seatIndex + 1,
              label: `${String.fromCharCode(65 + rowIndex)}-${seatIndex + 1}`,
              status: (taken.includes(id) ? 'SOLD' : 'AVAILABLE') as SeatStatus,
              version: 0,
              priceCents: price,
            }
          }),
        })),
      },
    ],
  }
}

describe('SeatListMode', () => {
  it('lists only seats that can be chosen', () => {
    // A list of five thousand entries of which four thousand cannot be chosen is not more
    // accessible than a grid; it is a grid with the geometry removed.
    const store = new SeatMapStore(buildMap(2, 3, ['seat-0', 'seat-1']))
    render(<SeatListMode store={store} revision={0} selectedIds={new Set()} onSelect={vi.fn()} />)

    expect(screen.getByRole('status')).toHaveTextContent('4 seats match')
    expect(screen.queryByRole('button', { name: /row A, seat 1/ })).not.toBeInTheDocument()
  })

  it('can include unavailable seats when asked', async () => {
    const user = userEvent.setup()
    const store = new SeatMapStore(buildMap(2, 3, ['seat-0']))
    render(<SeatListMode store={store} revision={0} selectedIds={new Set()} onSelect={vi.fn()} />)

    await user.click(screen.getByLabelText(/Include seats that are not for sale/))

    expect(screen.getByRole('status')).toHaveTextContent('6 seats match')
    expect(screen.getByRole('button', { name: /row A, seat 1.*sold/ })).toBeInTheDocument()
  })

  it('filters by price, which is what a list is better than a grid at', async () => {
    const user = userEvent.setup()
    const store = new SeatMapStore(buildMap(2, 2, [], [3000, 9000]))
    render(<SeatListMode store={store} revision={0} selectedIds={new Set()} onSelect={vi.fn()} />)

    await user.selectOptions(screen.getByLabelText(/Maximum price/), '3000')

    expect(screen.getByRole('status')).toHaveTextContent('2 seats match')
  })

  it('states each seat in words, including its price and state', () => {
    const store = new SeatMapStore(buildMap(1, 1))
    render(<SeatListMode store={store} revision={0} selectedIds={new Set()} onSelect={vi.fn()} />)

    expect(
      screen.getByRole('button', { name: 'Floor, row A, seat 1 — $45.00 — available' }),
    ).toBeInTheDocument()
  })

  it('marks a selected seat as pressed rather than only colouring it', () => {
    const store = new SeatMapStore(buildMap(1, 2))
    render(
      <SeatListMode store={store} revision={0} selectedIds={new Set(['seat-0'])} onSelect={vi.fn()} />,
    )

    expect(screen.getByRole('button', { name: /seat 1/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /seat 2/ })).toHaveAttribute('aria-pressed', 'false')
  })

  it('selects a seat on click', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    const store = new SeatMapStore(buildMap(1, 2))
    render(<SeatListMode store={store} revision={0} selectedIds={new Set()} onSelect={onSelect} />)

    await user.click(screen.getByRole('button', { name: /seat 2/ }))

    expect(onSelect).toHaveBeenCalledWith('seat-1')
  })

  it('does not select an unavailable seat', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    const store = new SeatMapStore(buildMap(1, 2, ['seat-1']))
    render(<SeatListMode store={store} revision={0} selectedIds={new Set()} onSelect={onSelect} />)

    await user.click(screen.getByLabelText(/Include seats that are not for sale/))
    await user.click(screen.getByRole('button', { name: /seat 2.*sold/ }))

    expect(onSelect).not.toHaveBeenCalled()
  })

  it('caps the list and says it has, rather than rendering thousands of entries', () => {
    const store = new SeatMapStore(buildMap(20, 40))
    render(<SeatListMode store={store} revision={0} selectedIds={new Set()} onSelect={vi.fn()} />)

    expect(screen.getByRole('status')).toHaveTextContent('800 seats match')
    expect(screen.getAllByRole('listitem')).toHaveLength(500)
    expect(screen.getByText(/Showing the first 500 of 800/)).toBeInTheDocument()
  })
})
