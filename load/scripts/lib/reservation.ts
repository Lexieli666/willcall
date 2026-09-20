import http from 'k6/http'
import type { RefinedResponse, ResponseType } from 'k6/http'
import { BASE_URL, json, uuid } from './common.ts'

export interface EventSummary {
  id: string
  name: string
  capacity: number
  holdTtlSeconds: number
  maxSeatsPerOrder: number
  status: string
  waitingRoomEnabled: boolean
}

export interface SeatMapResponse {
  eventId: string
  sequence: number
  availableCount: number
  heldCount: number
  soldCount: number
  sections: Array<{ rows: Array<{ seats: Array<{ id: string; status: string }> }> }>
}

export interface HoldResponse {
  holdId: string
  seatIds: string[]
}

export interface QueueResponse {
  state: string
  position: number
  queueLength: number
  estimatedWaitSeconds: number | null
  beyondInventory: boolean
  admissionToken: string | null
}

/** A distinct buyer reference per virtual user, stable for the run. */
export function buyerRef(prefix: string, vu: number, iteration = 0): string {
  return `${prefix}-${String(vu).padStart(6, '0')}-${String(iteration).padStart(4, '0')}`
}

export function createEvent(options: {
  name: string
  rowCount: number
  seatsPerRow: number
  holdTtlSeconds?: number
  maxSeatsPerOrder?: number
  waitingRoomEnabled?: boolean
  admissionRatePerSecond?: number
}): EventSummary {
  const response = http.post(
    `${BASE_URL}/api/events`,
    JSON.stringify({
      venueName: 'Load Arena',
      eventName: options.name,
      holdTtlSeconds: options.holdTtlSeconds ?? 120,
      maxSeatsPerOrder: options.maxSeatsPerOrder ?? 4,
      status: 'ON_SALE',
      priceTiers: [{ name: 'Standard', amountCents: 4500, currency: 'USD' }],
      sections: [
        {
          name: 'Floor',
          rowCount: options.rowCount,
          seatsPerRow: options.seatsPerRow,
          priceTierName: 'Standard',
        },
      ],
      waitingRoomEnabled: options.waitingRoomEnabled ?? false,
      admissionRatePerSecond: options.admissionRatePerSecond ?? null,
    }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'create_event' } },
  )
  const event = json<EventSummary>(response)
  if (!event) throw new Error(`could not create an event: ${response.status} ${response.body}`)
  return event
}

export interface AvailabilityResponse {
  eventId: string
  capacity: number
  available: number
  held: number
  sold: number
}

/**
 * Just the counts.
 *
 * <p>Used by anything that polls during a run. Fetching the full seat map instead serialises
 * thousands of rows on every poll, and at four polls a second that is enough load to change the
 * result being measured — which it did, the first time.
 */
export function availability(eventId: string): AvailabilityResponse | null {
  return json<AvailabilityResponse>(
    http.get(`${BASE_URL}/api/events/${eventId}/availability`, { tags: { endpoint: 'availability' } }),
  )
}

export function seatMap(eventId: string): SeatMapResponse | null {
  return json<SeatMapResponse>(
    http.get(`${BASE_URL}/api/events/${eventId}/seats`, { tags: { endpoint: 'seat_map' } }),
  )
}

export function availableSeatIds(eventId: string): string[] {
  const map = seatMap(eventId)
  if (!map) return []
  const ids: string[] = []
  for (const section of map.sections) {
    for (const row of section.rows) {
      for (const seat of row.seats) {
        if (seat.status === 'AVAILABLE') ids.push(seat.id)
      }
    }
  }
  return ids
}

export function joinQueue(eventId: string, buyer: string): RefinedResponse<ResponseType | undefined> {
  return http.post(`${BASE_URL}/api/events/${eventId}/queue/join`, null, {
    headers: { 'X-Willcall-User': buyer },
    tags: { endpoint: 'queue_join' },
  })
}

export function queuePosition(eventId: string, buyer: string): RefinedResponse<ResponseType | undefined> {
  return http.get(`${BASE_URL}/api/events/${eventId}/queue/me`, {
    headers: { 'X-Willcall-User': buyer },
    tags: { endpoint: 'queue_me' },
  })
}

/** Holds seats. `admissionToken` is required when the event has a waiting room. */
export function hold(
  eventId: string,
  buyer: string,
  body: { seatIds?: string[]; quantity?: number; together?: boolean },
  admissionToken?: string | null,
): RefinedResponse<ResponseType | undefined> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Willcall-User': buyer,
    'Idempotency-Key': uuid(),
  }
  if (admissionToken) headers['X-Willcall-Admission'] = admissionToken
  return http.post(`${BASE_URL}/api/events/${eventId}/holds`, JSON.stringify(body), {
    headers,
    tags: { endpoint: 'hold' },
  })
}

export function confirm(
  holdId: string,
  buyer: string,
  paymentBehavior?: string,
): RefinedResponse<ResponseType | undefined> {
  return http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify(paymentBehavior ? { holdId, paymentBehavior } : { holdId }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Willcall-User': buyer,
        'Idempotency-Key': uuid(),
      },
      tags: { endpoint: 'confirm' },
    },
  )
}

/** Asserts the central invariant through the admin endpoint at the end of a run. */
export function verifyInvariants(): boolean {
  const response = http.post(`${BASE_URL}/api/admin/verify-invariants`, null, {
    tags: { endpoint: 'verify_invariants' },
  })
  const report = json<{ passed: boolean }>(response)
  return response.status === 200 && report?.passed === true
}
