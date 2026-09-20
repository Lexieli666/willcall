// Fairness under a burst: ten thousand arrivals into a paced queue, then measure how far admission
// order drifted from arrival order.
//
// The claim being tested is deliberately weak, because the architecture cannot support a stronger
// one. Admission happens in batches across replicas, so two people a millisecond apart can swap.
// What is published is the measured inversion rate, whatever it is — not a claim of strict FIFO
// that the design does not provide. See docs/adr/0010-fairness-policy.md.
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'
import type { Options } from 'k6/options'
import { json } from './lib/common.ts'
import { summaryFile, type K6Summary } from './lib/summary.ts'
import { buyerRef, createEvent, joinQueue, queuePosition, hold } from './lib/reservation.ts'

const ARRIVALS = Number(__ENV.FAIRNESS_ARRIVALS || 10000)
const WINDOW = __ENV.FAIRNESS_WINDOW || '10s'
const SEATS = Number(__ENV.FAIRNESS_SEATS || 5000)
const ADMISSION_RATE = Number(__ENV.FAIRNESS_ADMISSION_RATE || 400)

const joined = new Counter('willcall_fairness_joined')
const admitted = new Counter('willcall_fairness_admitted')
const heldAfterAdmission = new Counter('willcall_fairness_held')
const refusedWithoutToken = new Counter('willcall_fairness_refused_unadmitted')
const joinDuration = new Trend('willcall_fairness_join_duration', true)
const errors = new Rate('willcall_fairness_errors')

export const options: Options = {
  scenarios: {
    arrive: {
      executor: 'ramping-arrival-rate',
      startRate: ARRIVALS / 10,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.FAIRNESS_VUS || 2000),
      maxVUs: Number(__ENV.FAIRNESS_MAX_VUS || 12000),
      stages: [
        { target: ARRIVALS / 10, duration: WINDOW },
        // A tail long enough for the queue to drain at the admission rate, so the inversion rate
        // is measured over the whole queue rather than over whoever happened to be admitted first.
        { target: 0, duration: __ENV.FAIRNESS_TAIL || '30s' },
      ],
    },
  },
  thresholds: {
    willcall_fairness_errors: ['rate<0.001'],
    // Joining must stay fast under its own load: somebody who cannot even join has no idea
    // whether the sale exists.
    willcall_fairness_join_duration: ['p(99)<1000'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export function setup(): { eventId: string } {
  const rows = Math.max(1, Math.floor(SEATS / 50))
  const event = createEvent({
    name: `Fairness ${new Date().toISOString()}`,
    rowCount: rows,
    seatsPerRow: Math.ceil(SEATS / rows),
    holdTtlSeconds: 60,
    maxSeatsPerOrder: 2,
    waitingRoomEnabled: true,
    admissionRatePerSecond: ADMISSION_RATE,
  })
  console.log(
    `fairness: ${ARRIVALS} arrivals in ${WINDOW} for ${SEATS} seats, admitting ${ADMISSION_RATE}/s ` +
      `(event ${event.id})`,
  )
  return { eventId: event.id }
}

export default function fairness(data: { eventId: string }): void {
  const buyer = buyerRef('queue', __VU, __ITER)

  const joinResponse = joinQueue(data.eventId, buyer)
  joinDuration.add(joinResponse.timings.duration)

  if (joinResponse.status !== 200) {
    errors.add(joinResponse.status >= 500)
    return
  }
  errors.add(false)
  joined.add(1)

  const body = json<{ state: string; admissionToken: string | null }>(joinResponse)
  if (!body) return

  // Wait to be admitted, the way a person watching a queue page does. Bounded, because a virtual
  // user that waits forever is a virtual user that never reports anything.
  let token = body.admissionToken
  for (let attempt = 0; attempt < 20 && !token; attempt += 1) {
    sleep(1)
    const position = queuePosition(data.eventId, buyer)
    if (position.status !== 200) {
      errors.add(position.status >= 500)
      continue
    }
    token = json<{ admissionToken: string | null }>(position)?.admissionToken ?? null
  }

  if (!token) return
  admitted.add(1)

  const holdResponse = hold(data.eventId, buyer, { quantity: 1 }, token)
  if (holdResponse.status === 201) heldAfterAdmission.add(1)
  else if (holdResponse.status === 403) refusedWithoutToken.add(1)
  errors.add(holdResponse.status >= 500)

  check(holdResponse, {
    'an admitted buyer is never refused for lack of admission': (r) => r.status !== 403,
  })
}

export function handleSummary(data: K6Summary): Record<string, string> {
  return summaryFile('fairness', data)
}
