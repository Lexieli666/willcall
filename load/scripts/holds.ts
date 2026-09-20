// Sustained hold traffic at a fixed arrival rate.
//
// This is the scenario the capacity model is built from, so it is an arrival-rate executor rather
// than a fixed pool of virtual users. With a VU pool, a slowing service produces fewer requests
// and the measured latency stays flat while throughput quietly collapses — the load adapts to the
// service instead of testing it. A constant arrival rate keeps the offered load fixed and lets the
// queue build, which is what reveals the actual ceiling.
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'
import type { Options } from 'k6/options'
import { buyerRef, createEvent, hold } from './lib/reservation.ts'
import { summaryFile, type K6Summary } from './lib/summary.ts'

const holdErrors = new Rate('willcall_hold_errors')
const holdGranted = new Counter('willcall_holds_granted')
const holdRefused = new Counter('willcall_holds_refused')
const holdDuration = new Trend('willcall_hold_duration', true)

export const options: Options = {
  scenarios: {
    holds: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.HOLD_RATE || 1000),
      timeUnit: '1s',
      duration: __ENV.HOLD_DURATION || '60s',
      preAllocatedVUs: Number(__ENV.HOLD_VUS || 500),
      maxVUs: Number(__ENV.HOLD_MAX_VUS || 4000),
    },
  },
  thresholds: {
    // The specification's band for hold p99 at about 1,000 requests per second on three replicas.
    // Set as a threshold so a regression fails the run rather than being noticed in a chart.
    willcall_hold_duration: ['p(99)<150'],
    // A 409 is a correct answer, not a failure, so the error rate counts only 5xx and transport
    // failures. Counting 409s here would make a sold-out event look like an outage.
    willcall_hold_errors: ['rate<0.001'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export function setup(): { eventId: string } {
  // Large enough that the run does not sell out and start measuring the sold-out path instead of
  // the hold path.
  const event = createEvent({
    name: `Hold load ${new Date().toISOString()}`,
    rowCount: 400,
    seatsPerRow: 100,
    holdTtlSeconds: 20,
    maxSeatsPerOrder: 4,
  })
  console.log(`holds scenario using event ${event.id} with capacity ${event.capacity}`)
  return { eventId: event.id }
}

export default function holds(data: { eventId: string }): void {
  const buyer = buyerRef('holder', __VU, __ITER)
  const response = hold(data.eventId, buyer, { quantity: 1 })

  holdDuration.add(response.timings.duration)

  if (response.status === 201) holdGranted.add(1)
  else if (response.status === 409) holdRefused.add(1)

  holdErrors.add(
    !check(response, {
      'no server error': (r) => r.status < 500,
      'answered': (r) => r.status !== 0,
    }),
  )

  sleep(0.05)
}

/**
 * k6 calls this at the end. It writes a second summary carrying the scenario name and each
 * threshold's verdict, so RESULTS_SUMMARY.md can be generated from raw output rather than
 * assembled by hand.
 */
export function handleSummary(data: K6Summary): Record<string, string> {
  return summaryFile('holds', data)
}
