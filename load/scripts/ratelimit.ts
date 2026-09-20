// Rate limiting: a single buyer hammering the API must be slowed with 429 and a Retry-After, not
// dropped, and must not be able to degrade the service for anyone else.
//
// The property being tested is not "the limiter exists" but "the limiter answers usefully". A
// client that receives a bare 429 with no Retry-After retries immediately, which turns rate
// limiting into a busy loop and makes the problem worse.
import { check, sleep } from 'k6'
import { Counter, Rate } from 'k6/metrics'
import type { Options } from 'k6/options'
import { buyerRef, createEvent, hold } from './lib/reservation.ts'
import { summaryFile, type K6Summary } from './lib/summary.ts'

const limited = new Counter('willcall_rate_limited')
const served = new Counter('willcall_rate_served')
const missingRetryAfter = new Counter('willcall_missing_retry_after')
const errors = new Rate('willcall_ratelimit_errors')

export const options: Options = {
  scenarios: {
    // One abusive buyer, going as fast as it can.
    abusive: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.ABUSE_RATE || 400),
      timeUnit: '1s',
      duration: __ENV.ABUSE_DURATION || '30s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      exec: 'abusive',
    },
    // Ordinary traffic alongside it. The point is that these requests keep succeeding.
    polite: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.POLITE_RATE || 50),
      timeUnit: '1s',
      duration: __ENV.ABUSE_DURATION || '30s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      exec: 'polite',
    },
  },
  thresholds: {
    // Every 429 must carry a Retry-After; without one a client retries immediately and the
    // limiter makes things worse rather than better.
    willcall_missing_retry_after: ['count==0'],
    willcall_ratelimit_errors: ['rate<0.001'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export function setup(): { eventId: string } {
  const event = createEvent({
    name: `Rate limit ${new Date().toISOString()}`,
    rowCount: 200,
    seatsPerRow: 50,
    holdTtlSeconds: 15,
  })
  return { eventId: event.id }
}

function attempt(eventId: string, buyer: string): void {
  const response = hold(eventId, buyer, { quantity: 1 })

  if (response.status === 429) {
    limited.add(1)
    if (!response.headers['Retry-After']) missingRetryAfter.add(1)
    const seconds = Number(response.headers['Retry-After'] ?? 1)
    sleep(Math.min(seconds, 5))
    return
  }

  if (response.status >= 500) {
    errors.add(true)
    return
  }
  errors.add(false)
  served.add(1)
}

/** One buyer reference, reused, so the limiter has a single identity to slow down. */
export function abusive(data: { eventId: string }): void {
  attempt(data.eventId, 'abusive-buyer-000001')
}

/** A different buyer per iteration: ordinary traffic that must keep working. */
export function polite(data: { eventId: string }): void {
  const buyer = buyerRef('polite', __VU, __ITER)
  const response = hold(data.eventId, buyer, { quantity: 1 })
  errors.add(response.status >= 500)
  check(response, {
    'ordinary traffic is not rate limited': (r) => r.status !== 429,
    'ordinary traffic is answered': (r) => r.status === 201 || r.status === 409,
  })
  sleep(0.1)
}

/**
 * k6 calls this at the end. It writes a second summary carrying the scenario name and each
 * threshold's verdict, so RESULTS_SUMMARY.md can be generated from raw output rather than
 * assembled by hand.
 */
export function handleSummary(data: K6Summary): Record<string, string> {
  return summaryFile('ratelimit', data)
}
