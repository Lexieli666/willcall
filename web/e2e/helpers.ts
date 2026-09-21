import { expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * The section every fixture event uses, and the accessible name a seat in it will have.
 *
 * The server builds a seat's label as `section-row` and the front end announces
 * `Seat <label>-<number>, ...`, so a seat in row B, position 3 reads "Seat Floor-B-3". Three
 * keyboard assertions were written against `/Seat B-3/` and had never run to completion, so the
 * mismatch sat in the suite unnoticed - the section qualifier is deliberate, because "B-3" is
 * ambiguous the moment a venue has two sections.
 *
 * Deriving it here rather than repeating the shape in each spec means a change to seat naming
 * breaks one line instead of silently un-asserting several.
 */
export const SECTION_NAME = 'Floor'

/** The accessible-name fragment for a seat, e.g. seatLabel('B', 3) -> "Seat Floor-B-3". */
export function seatLabel(row: string, seatNumber: number): string {
  return `Seat ${SECTION_NAME}-${row}-${seatNumber}`
}

export const API_ORIGIN = process.env.WILLCALL_API_ORIGIN ?? 'http://127.0.0.1:8080'

export interface CreatedEvent {
  id: string
  capacity: number
}

/** Creates an event straight through the API, so a UI test is not also a setup test. */
export async function createEvent(
  request: APIRequestContext,
  options: {
    name?: string
    rowCount?: number
    seatsPerRow?: number
    holdTtlSeconds?: number
    maxSeatsPerOrder?: number
  } = {},
): Promise<CreatedEvent> {
  const rowCount = options.rowCount ?? 5
  const seatsPerRow = options.seatsPerRow ?? 10

  const response = await request.post(`${API_ORIGIN}/api/events`, {
    data: {
      venueName: 'E2E Arena',
      eventName: options.name ?? `E2E Event ${Date.now()}`,
      holdTtlSeconds: options.holdTtlSeconds ?? 120,
      maxSeatsPerOrder: options.maxSeatsPerOrder ?? 8,
      status: 'ON_SALE',
      priceTiers: [{ name: 'Standard', amountCents: 4500, currency: 'USD' }],
      sections: [{ name: SECTION_NAME, rowCount, seatsPerRow, priceTierName: 'Standard' }],
    },
  })
  expect(response.status(), await response.text()).toBe(201)
  const body = (await response.json()) as { id: string; capacity: number }
  return { id: body.id, capacity: body.capacity }
}

/** Takes a hold as some other buyer, which is how a test makes a seat disappear under the user. */
export async function holdAsSomeoneElse(
  request: APIRequestContext,
  eventId: string,
  seatIds: string[],
): Promise<string> {
  const response = await request.post(`${API_ORIGIN}/api/events/${eventId}/holds`, {
    headers: {
      'X-Willcall-User': `rival-${Math.random().toString(36).slice(2, 12)}`,
      'Idempotency-Key': crypto.randomUUID(),
      'Content-Type': 'application/json',
    },
    data: { seatIds },
  })
  expect(response.status(), await response.text()).toBe(201)
  return ((await response.json()) as { holdId: string }).holdId
}

export async function seatIdsOf(
  request: APIRequestContext,
  eventId: string,
  count: number,
): Promise<string[]> {
  const response = await request.get(`${API_ORIGIN}/api/events/${eventId}/seats`)
  const body = (await response.json()) as {
    sections: Array<{ rows: Array<{ seats: Array<{ id: string; status: string }> }> }>
  }
  const seats = body.sections
    .flatMap((section) => section.rows)
    .flatMap((row) => row.seats)
    .filter((seat) => seat.status === 'AVAILABLE')
  return seats.slice(0, count).map((seat) => seat.id)
}

/** Burns a sequence number on the server without publishing it: a genuinely lost message. */
export async function injectGap(request: APIRequestContext, eventId: string): Promise<void> {
  const response = await request.post(`${API_ORIGIN}/api/admin/test/events/${eventId}/inject-gap`)
  expect(
    response.status(),
    'the gap-injection hook must be enabled (WILLCALL_TEST_HOOKS=true)',
  ).toBe(200)
}

export async function expireAllHolds(request: APIRequestContext): Promise<void> {
  const response = await request.post(`${API_ORIGIN}/api/admin/test/expire-holds`)
  expect(response.status()).toBe(200)
}

/** Reads the diagnostics the event page publishes for the tests and the organizer view. */
export async function streamStats(page: Page): Promise<{ status: string; text: string }> {
  const element = page.getByTestId('stream-status')
  await expect(element).toBeVisible()
  return { status: (await element.textContent()) ?? '', text: (await element.innerText()) ?? '' }
}
