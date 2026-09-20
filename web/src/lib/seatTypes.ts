export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD' | 'BLOCKED'

/** A seat as the seat-map endpoint returns it. */
export interface SeatView {
  id: string
  rowId: string
  number: number
  label: string
  status: SeatStatus
  version: number
  priceCents: number
}

export interface RowView {
  id: string
  label: string
  displayOrder: number
  seats: SeatView[]
}

export interface SectionView {
  id: string
  name: string
  displayOrder: number
  rows: RowView[]
}

export interface SeatMapResponse {
  eventId: string
  sequence: number
  sections: SectionView[]
  availableCount: number
  heldCount: number
  soldCount: number
}

export interface SeatCounts {
  available: number
  held: number
  sold: number
}

/** One seat's new state, as carried on the stream. */
export interface SeatChange {
  id: string
  status: SeatStatus
  version: number
  /**
   * When the transaction that changed this seat committed.
   *
   * <p>The browser does not use it; the load generator does, to measure propagation from the
   * commit rather than from whenever the fan-out ran. It is typed here so a change to the field
   * cannot silently break that measurement.
   */
  committedAt?: string | null
}

export interface SnapshotMessage {
  eventId: string
  sequence: number
  serverTime: string
  coalesceWindowMs: number
  seats: SeatChange[]
  counts: SeatCounts | null
}

export interface DeltaMessage {
  /**
   * The lowest sequence number this frame accounts for.
   *
   * <p>A coalesced frame consumes several sequence numbers and sends one message, so checking
   * only the highest would make every multi-seat hold look like a gap. The client checks that
   * this equals its cursor plus one and then advances to {@link DeltaMessage.sequence}.
   */
  fromSequence: number
  /** The highest sequence number this frame accounts for. */
  sequence: number
  changes: SeatChange[]
  counts: SeatCounts | null
}

export interface ResyncMessage {
  reason: string
  sequence: number
}

export interface EventSummary {
  id: string
  name: string
  startsAt: string
  salesOpenAt: string
  capacity: number
  holdTtlSeconds: number
  maxSeatsPerOrder: number
  status: 'DRAFT' | 'ON_SALE' | 'PAUSED' | 'CLOSED'
  lastSequence: number
}

export interface HoldResponse {
  holdId: string
  eventId: string
  seatIds: string[]
  expiresAt: string
  secondsRemaining: number
  status: string
}

export interface OrderResponse {
  orderId: string
  eventId: string
  status: string
  totalCents: number
  currency: string
  seatIds: string[]
  paymentReference: string | null
  confirmedAt: string | null
}

/** A buyer's own place in the waiting room, pushed on the same stream as the seat map. */
export interface QueueFrame {
  state: 'WAITING' | 'ADMITTED' | 'DEGRADED_OPEN' | 'NOT_QUEUED'
  position: number
  queueLength: number
  estimatedWaitSeconds: number | null
  beyondInventory: boolean
  admissionToken: string | null
  serverTime: string
}

export interface BuyerState {
  eventId: string
  userRef: string
  serverTime: string
  holds: Array<{
    holdId: string
    seatIds: string[]
    expiresAt: string
    secondsRemaining: number
    status: string
  }>
  orders: Array<{ orderId: string; status: string; totalCents: number; seatIds: string[] }>
  availableSeats: number
}
