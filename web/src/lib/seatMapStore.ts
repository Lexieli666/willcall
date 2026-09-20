import type { SeatChange, SeatCounts, SeatMapResponse, SeatStatus, SeatView } from './seatTypes'

/**
 * The client's view of the seat map.
 *
 * <h2>Why the shape is what it is</h2>
 *
 * Five thousand seats held as nested React state, with a delta replacing seat objects, produces
 * thousands of new object identities per update and re-renders the whole venue. This store instead
 * keeps:
 *
 * <ul>
 *   <li><b>One array per row, mutated in place.</b> The array identity never changes, so a
 *       memoised row component is not invalidated by an update to a different row.
 *   <li><b>A revision counter per row.</b> That is the single primitive prop a row is memoised on.
 *       A delta touching two seats bumps two counters and repaints two rows.
 *   <li><b>A stable seat list per row</b>, built once. Slicing a flat array during render would
 *       hand every row a fresh array on every pass and defeat memoisation entirely — the mistake
 *       this shape exists to prevent.
 * </ul>
 *
 * <p>Versions are correctness, not performance: a change applies only when its version exceeds the
 * one held, which makes duplicate and out-of-order delivery harmless.
 */
export interface StoreRow {
  id: string
  label: string
  sectionId: string
  sectionName: string
  /** Stable for the life of the store. */
  seats: SeatView[]
  /** Mutated in place; identity is stable. Read together with {@link StoreRow.revision}. */
  statuses: SeatStatus[]
  /** Incremented whenever any seat in this row changes. The row component's only memo key. */
  revision: number
}

export interface StoreSection {
  id: string
  name: string
  rows: StoreRow[]
}

export class SeatMapStore {
  readonly sections: StoreSection[] = []
  readonly rows: StoreRow[] = []
  readonly seatCount: number

  /** seat id -> [row index, index within the row]. */
  private readonly positionById = new Map<string, [number, number]>()
  private readonly versions = new Map<string, number>()

  counts: SeatCounts
  sequence: number

  constructor(map: SeatMapResponse) {
    this.sequence = map.sequence
    this.counts = { available: map.availableCount, held: map.heldCount, sold: map.soldCount }

    let total = 0
    for (const section of map.sections) {
      const storeSection: StoreSection = { id: section.id, name: section.name, rows: [] }
      for (const row of section.rows) {
        const rowIndex = this.rows.length
        const storeRow: StoreRow = {
          id: row.id,
          label: row.label,
          sectionId: section.id,
          sectionName: section.name,
          seats: row.seats,
          statuses: row.seats.map((seat) => seat.status),
          revision: 0,
        }
        row.seats.forEach((seat, seatIndex) => {
          this.positionById.set(seat.id, [rowIndex, seatIndex])
          this.versions.set(seat.id, seat.version)
          total += 1
        })
        this.rows.push(storeRow)
        storeSection.rows.push(storeRow)
      }
      this.sections.push(storeSection)
    }
    this.seatCount = total
  }

  get size(): number {
    return this.seatCount
  }

  statusOf(seatId: string): SeatStatus | undefined {
    const position = this.positionById.get(seatId)
    if (!position) return undefined
    return this.rows[position[0]]?.statuses[position[1]]
  }

  versionOf(seatId: string): number | undefined {
    return this.versions.get(seatId)
  }

  rowIndexOf(seatId: string): number | undefined {
    return this.positionById.get(seatId)?.[0]
  }

  /**
   * Applies a batch of changes in place.
   *
   * @returns the row indices that actually changed; empty when every change was a duplicate
   */
  apply(changes: SeatChange[], counts: SeatCounts | null, sequence: number): number[] {
    const dirty = new Set<number>()

    for (const change of changes) {
      const position = this.positionById.get(change.id)
      if (!position) continue // a seat this client never loaded

      // The version guard. Without it a delta arriving twice, or out of order after a reconnect,
      // would move a seat backwards and the map would show a sold seat as available.
      const held = this.versions.get(change.id) ?? -1
      if (change.version <= held) continue

      const row = this.rows[position[0]]
      if (!row) continue

      this.versions.set(change.id, change.version)
      row.statuses[position[1]] = change.status
      dirty.add(position[0])
    }

    for (const rowIndex of dirty) {
      const row = this.rows[rowIndex]
      if (row) row.revision += 1
    }

    if (counts) this.counts = counts
    else if (dirty.size > 0) this.recount()
    this.sequence = sequence

    return [...dirty]
  }

  /** Recomputes the counts from the seats themselves, for when the server sent none. */
  recount(): SeatCounts {
    let available = 0
    let held = 0
    let sold = 0
    for (const row of this.rows) {
      for (const status of row.statuses) {
        if (status === 'AVAILABLE') available += 1
        else if (status === 'HELD') held += 1
        else if (status === 'SOLD') sold += 1
      }
    }
    this.counts = { available, held, sold }
    return this.counts
  }

  /** Every seat, in map order. Used by the text-only list mode. */
  allSeats(): Array<{ seat: SeatView; status: SeatStatus; row: StoreRow }> {
    const out: Array<{ seat: SeatView; status: SeatStatus; row: StoreRow }> = []
    for (const row of this.rows) {
      row.seats.forEach((seat, index) => {
        out.push({ seat, status: row.statuses[index] ?? seat.status, row })
      })
    }
    return out
  }
}
