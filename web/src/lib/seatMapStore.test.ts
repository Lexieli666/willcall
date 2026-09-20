import { describe, expect, it } from 'vitest'
import { SeatMapStore } from './seatMapStore'
import type { SeatMapResponse } from './seatTypes'

function buildMap(rows: number, seatsPerRow: number): SeatMapResponse {
  let n = 0
  return {
    eventId: 'event-1',
    sequence: 100,
    availableCount: rows * seatsPerRow,
    heldCount: 0,
    soldCount: 0,
    sections: [
      {
        id: 'section-1',
        name: 'Floor',
        displayOrder: 0,
        rows: Array.from({ length: rows }, (_, rowIndex) => ({
          id: `row-${rowIndex}`,
          label: String.fromCharCode(65 + (rowIndex % 26)),
          displayOrder: rowIndex,
          seats: Array.from({ length: seatsPerRow }, (_, seatIndex) => ({
            id: `seat-${n++}`,
            rowId: `row-${rowIndex}`,
            number: seatIndex + 1,
            label: `R${rowIndex}-${seatIndex + 1}`,
            status: 'AVAILABLE',
            version: 0,
            priceCents: 2500,
          })),
        })),
      },
    ],
  }
}

describe('SeatMapStore', () => {
  it('indexes every seat and keeps rows addressable', () => {
    const store = new SeatMapStore(buildMap(3, 4))

    expect(store.size).toBe(12)
    expect(store.rows).toHaveLength(3)
    expect(store.rowIndexOf('seat-5')).toBe(1)
    expect(store.statusOf('seat-5')).toBe('AVAILABLE')
  })

  it('applies a change, bumps only that row, and leaves other rows untouched', () => {
    const store = new SeatMapStore(buildMap(3, 4))
    const untouched = store.rows[0]!.revision

    const dirty = store.apply([{ id: 'seat-6', status: 'SOLD', version: 1 }], null, 101)

    expect(store.statusOf('seat-6')).toBe('SOLD')
    expect(dirty).toEqual([1])
    expect(store.rows[1]!.revision).toBe(1)
    expect(store.rows[0]!.revision).toBe(untouched)
    expect(store.sequence).toBe(101)
  })

  it('keeps row array identity stable, which is what makes memoisation work', () => {
    const store = new SeatMapStore(buildMap(2, 3))
    const statusesBefore = store.rows[0]!.statuses
    const seatsBefore = store.rows[0]!.seats

    store.apply([{ id: 'seat-0', status: 'HELD', version: 1 }], null, 101)

    expect(store.rows[0]!.statuses).toBe(statusesBefore)
    expect(store.rows[0]!.seats).toBe(seatsBefore)
  })

  it('ignores a delta whose version is not newer, so a duplicate cannot move a seat backwards', () => {
    const store = new SeatMapStore(buildMap(1, 4))
    store.apply([{ id: 'seat-0', status: 'SOLD', version: 5 }], null, 101)

    const dirty = store.apply([{ id: 'seat-0', status: 'AVAILABLE', version: 3 }], null, 102)

    expect(store.statusOf('seat-0')).toBe('SOLD')
    expect(dirty).toEqual([])
    expect(store.rows[0]!.revision).toBe(1)
  })

  it('ignores the same version arriving twice', () => {
    const store = new SeatMapStore(buildMap(1, 4))
    store.apply([{ id: 'seat-0', status: 'HELD', version: 2 }], null, 101)

    expect(store.apply([{ id: 'seat-0', status: 'HELD', version: 2 }], null, 102)).toEqual([])
  })

  it('ignores a change for a seat it has never heard of', () => {
    const store = new SeatMapStore(buildMap(1, 2))

    expect(() => store.apply([{ id: 'unknown', status: 'SOLD', version: 9 }], null, 101)).not.toThrow()
    expect(store.counts.available).toBe(2)
  })

  it('recounts from the seats when the server sent no counts', () => {
    const store = new SeatMapStore(buildMap(2, 3))

    store.apply(
      [
        { id: 'seat-0', status: 'SOLD', version: 1 },
        { id: 'seat-1', status: 'HELD', version: 1 },
      ],
      null,
      101,
    )

    expect(store.counts).toEqual({ available: 4, held: 1, sold: 1 })
  })

  it('applies five hundred changes to a five-thousand-seat map quickly', () => {
    const store = new SeatMapStore(buildMap(100, 50))
    expect(store.size).toBe(5000)

    const start = performance.now()
    const dirty = store.apply(
      Array.from({ length: 500 }, (_, i) => ({
        id: `seat-${i * 10}`,
        status: 'SOLD',
        version: 1,
      })),
      null,
      200,
    )
    const elapsed = performance.now() - start

    expect(store.statusOf('seat-4990')).toBe('SOLD')
    expect(dirty.length).toBeGreaterThan(0)
    // Generous, because this runs on whatever CI machine is free. It fails if apply() is ever
    // made to walk the seat list per change, which is the mistake being guarded against.
    expect(elapsed).toBeLessThan(50)
  })

  it('lists every seat in map order for the text-only mode', () => {
    const store = new SeatMapStore(buildMap(2, 2))
    store.apply([{ id: 'seat-3', status: 'SOLD', version: 1 }], null, 101)

    const all = store.allSeats()

    expect(all.map((entry) => entry.seat.id)).toEqual(['seat-0', 'seat-1', 'seat-2', 'seat-3'])
    expect(all[3]!.status).toBe('SOLD')
    expect(all[0]!.row.label).toBe('A')
  })
})
