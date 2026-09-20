// The flash sale: ten thousand buyers arriving inside ten seconds for five thousand seats.
//
// This is the headline scenario and the one most easily made meaningless. Three choices keep it
// honest:
//
//  1. **Arrival rate, not virtual users.** With a fixed VU pool, a slowing service produces fewer
//     requests, so latency looks flat while throughput collapses — the load adapts to the service
//     instead of testing it. `ramping-arrival-rate` keeps the offered load fixed.
//  2. **A 409 is a success.** Nine and a half thousand people are going to be turned away; that is
//     the product working. The error rate counts 5xx and transport failures only. Counting 409s
//     would make a correct sell-out look like an outage.
//  3. **Oversells are checked against the database**, not inferred from response codes. The run
//     ends by calling the invariant endpoint, and a threshold fails the run if it does not pass.
import { sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'
import type { Options } from 'k6/options'
import { json } from './lib/common.ts'
import {
  availability,
  availableSeatIds,
  buyerRef,
  confirm,
  createEvent,
  hold,
  seatMap,
  verifyInvariants,
} from './lib/reservation.ts'
import { summaryFile, type K6Summary } from './lib/summary.ts'

const SEATS = Number(__ENV.FLASH_SEATS || 5000)
const BUYERS = Number(__ENV.FLASH_BUYERS || 10000)
const ARRIVAL_WINDOW = __ENV.FLASH_WINDOW || '10s'

const granted = new Counter('willcall_flash_granted')
const refused = new Counter('willcall_flash_refused')
const confirmed = new Counter('willcall_flash_confirmed')
const serverErrors = new Counter('willcall_flash_server_errors')
/**
 * Requests the service deliberately refused because it was at capacity.
 *
 * <p>Counted separately from server errors, and from 409s, because they mean three different
 * things. A 409 is "somebody else got it", a 503 is "we are full, come back", and a 500 is "we are
 * broken". Only the last is a failure of the software, and folding 503 into it would make load
 * shedding — which is correct behaviour — indistinguishable from a fault.
 */
const shed = new Counter('willcall_flash_shed')
/**
 * Seconds from the first arrival to the moment no seat is available.
 *
 * <p>Measured by a watcher polling the seat map, not inferred from the run's duration: the run has
 * a deliberate tail after the arrival window so late holds can confirm, and counting that tail as
 * part of the sell-out would inflate the figure by twenty seconds.
 */
const soldOutSeconds = new Trend('willcall_flash_sold_out_seconds')
const holdDuration = new Trend('willcall_flash_hold_duration', true)
const confirmDuration = new Trend('willcall_flash_confirm_duration', true)
const invariantsHeld = new Rate('willcall_invariants_held')
const errorRate = new Rate('willcall_flash_errors')

export const options: Options = {
  scenarios: {
    // One virtual user watching the inventory, so the sell-out time is observed rather than
    // derived from the run's own duration.
    watcher: {
      executor: 'constant-vus',
      vus: 1,
      duration: __ENV.FLASH_WATCH || '40s',
      exec: 'watchInventory',
    },
    flash: {
      executor: 'ramping-arrival-rate',
      startRate: BUYERS / 10,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.FLASH_VUS || 2000),
      maxVUs: Number(__ENV.FLASH_MAX_VUS || 12000),
      stages: [
        // Everybody inside the arrival window, then a tail so the last holds can confirm.
        { target: BUYERS / 10, duration: ARRIVAL_WINDOW },
        { target: 0, duration: __ENV.FLASH_TAIL || '20s' },
      ],
    },
  },
  thresholds: {
    // Zero is the whole point. A single unexplained 5xx during a sell-out fails the run; a 503
    // is not one of those, because refusing work the service cannot do is the service working.
    willcall_flash_server_errors: ['count==0'],
    willcall_invariants_held: ['rate==1'],
    willcall_flash_errors: ['rate<0.001'],
    // Wide, because this scenario is about correctness under burst rather than about latency;
    // the hold percentile budget is measured by the holds scenario at a controlled rate.
    willcall_flash_hold_duration: ['p(99)<5000'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export function setup(): { eventId: string; capacity: number } {
  const rows = Math.max(1, Math.floor(SEATS / 50))
  const event = createEvent({
    name: `Flash sale ${new Date().toISOString()}`,
    rowCount: rows,
    seatsPerRow: Math.ceil(SEATS / rows),
    holdTtlSeconds: 30,
    maxSeatsPerOrder: 4,
  })
  const available = availableSeatIds(event.id).length
  console.log(
    `flash sale: ${BUYERS} buyers arriving in ${ARRIVAL_WINDOW} for ${available} seats (event ${event.id})`,
  )
  return { eventId: event.id, capacity: available }
}

let watchStartedAt = 0
let soldOutRecorded = false

/** Polls the seat map until nothing is available, and records when that happened. */
export function watchInventory(data: { eventId: string }): void {
  if (watchStartedAt === 0) watchStartedAt = Date.now()
  if (soldOutRecorded) {
    sleep(1)
    return
  }

  const map = availability(data.eventId)
  if (map && map.available === 0) {
    soldOutRecorded = true
    const seconds = (Date.now() - watchStartedAt) / 1000
    soldOutSeconds.add(seconds)
    console.log(`sold out after ${seconds.toFixed(1)} s`)
    return
  }
  sleep(0.5)
}

export default function flash(data: { eventId: string }): void {
  const buyer = buyerRef('flash', __VU, __ITER)

  const holdResponse = hold(data.eventId, buyer, { quantity: 1 })
  holdDuration.add(holdResponse.timings.duration)

  if (holdResponse.status === 503) {
    shed.add(1)
    return
  }
  if (holdResponse.status >= 500) {
    serverErrors.add(1)
    errorRate.add(true)
    return
  }
  errorRate.add(holdResponse.status === 0)

  if (holdResponse.status === 409) {
    // Sold out, or the seat went between the scan and the lock. Both are correct answers.
    refused.add(1)
    return
  }

  if (holdResponse.status !== 201) return

  granted.add(1)
  const body = json<{ holdId: string }>(holdResponse)
  if (!body) return

  // Most buyers who get a hold go on to pay. The one in ten who do not are the abandoned
  // checkouts the sweeper has to clean up, which is part of what this scenario exercises.
  //
  // Keyed on __VU, not __ITER. Under an arrival-rate executor almost every virtual user runs
  // exactly one iteration, so `__ITER % 10 === 0` was true for nearly all of them and the first
  // run of this suite confirmed zero seats and abandoned five hundred holds. The scenario looked
  // like it passed — the invariant held, no 5xx — while measuring nothing about checkout.
  if (__VU % 10 === 0) return

  const confirmResponse = confirm(body.holdId, buyer)
  confirmDuration.add(confirmResponse.timings.duration)

  if (confirmResponse.status === 503) {
    shed.add(1)
    return
  }
  if (confirmResponse.status >= 500) {
    serverErrors.add(1)
    errorRate.add(true)
    return
  }
  if (confirmResponse.status === 201) confirmed.add(1)

  sleep(0.1)
}

export function teardown(data: { eventId: string; capacity: number }): void {
  const passed = verifyInvariants()
  invariantsHeld.add(passed)

  // The final tally is printed as well as asserted: a threshold says pass or fail, and a reader
  // wants to know how many of the five thousand seats actually sold.
  const finalMap = seatMap(data.eventId)
  console.log(`invariants after the flash sale: ${passed ? 'PASS' : 'FAIL'}`)
  if (finalMap) {
    console.log(
      `final tally for event ${data.eventId} (capacity ${data.capacity}): ` +
        `${finalMap.soldCount} sold, ${finalMap.heldCount} held, ${finalMap.availableCount} available`,
    )
  }
}

/**
 * k6 calls this at the end. It writes a second summary carrying the scenario name and each
 * threshold's verdict, so RESULTS_SUMMARY.md can be generated from raw output rather than
 * assembled by hand.
 */
export function handleSummary(data: K6Summary): Record<string, string> {
  return summaryFile('flash', data)
}
