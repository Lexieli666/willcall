// Sustained waiting-room joins.
//
// What this is for: the queue sits in front of everything else, so if joining is slow the buyer
// never reaches the part that was carefully optimised. It also exercises the one path that is
// pure Redis, which makes it the scenario that shows what losing Redis would cost.
//
// Joining twice must be free and must not move anybody, so a third of the virtual users re-join
// deliberately. A run where re-joins are slower than first joins means the idempotent path is
// doing more work than the creating one, which would be backwards.
import { check, sleep } from 'k6'
import { Rate, Trend } from 'k6/metrics'
import type { Options } from 'k6/options'
import { buyerRef, createEvent, joinQueue, queuePosition } from './lib/reservation.ts'
import { json } from './lib/common.ts'
import { summaryFile, type K6Summary } from './lib/summary.ts'

const joinErrors = new Rate('willcall_join_errors')
const joinDuration = new Trend('willcall_join_duration', true)
const rejoinDuration = new Trend('willcall_rejoin_duration', true)
const positionDuration = new Trend('willcall_position_duration', true)

export const options: Options = {
  scenarios: {
    joins: {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.JOIN_START_RATE || 50),
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.JOIN_VUS || 200),
      maxVUs: Number(__ENV.JOIN_MAX_VUS || 2000),
      stages: [
        { target: Number(__ENV.JOIN_PEAK_RATE || 500), duration: __ENV.JOIN_RAMP || '30s' },
        { target: Number(__ENV.JOIN_PEAK_RATE || 500), duration: __ENV.JOIN_HOLD || '60s' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    willcall_join_errors: ['rate<0.01'],
    // The queue must stay fast under its own load: a buyer who cannot even join has no idea
    // whether the sale exists.
    willcall_join_duration: ['p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export function setup(): { eventId: string } {
  const event = createEvent({
    name: `Join load ${new Date().toISOString()}`,
    rowCount: 100,
    seatsPerRow: 50,
    waitingRoomEnabled: true,
    // Deliberately far below the join rate, so the queue actually builds up and positions are
    // meaningful. A rate high enough to admit everyone instantly would not be a queue test.
    admissionRatePerSecond: 20,
  })
  console.log(`joins scenario using event ${event.id} with capacity ${event.capacity}`)
  return { eventId: event.id }
}

export default function joins(data: { eventId: string }): void {
  const buyer = buyerRef('joiner', __VU, __ITER)

  const first = joinQueue(data.eventId, buyer)
  joinDuration.add(first.timings.duration)
  const joined = check(first, {
    'join 200': (r) => r.status === 200,
    'join reports a state': (r) => {
      const body = json<{ state: string }>(r)
      return body?.state === 'WAITING' || body?.state === 'ADMITTED'
    },
  })
  joinErrors.add(!joined)

  // A third re-join, because a refresh must be free and must not cost a place.
  if (__ITER % 3 === 0) {
    const again = joinQueue(data.eventId, buyer)
    rejoinDuration.add(again.timings.duration)
    joinErrors.add(
      !check(again, {
        'rejoin 200': (r) => r.status === 200,
        'rejoin keeps the position': (r) => {
          const before = json<{ position: number }>(first)
          const after = json<{ position: number }>(r)
          if (!before || !after) return false
          // The position can only improve as those ahead are admitted; it must never worsen.
          return after.position <= before.position
        },
      }),
    )
  }

  const position = queuePosition(data.eventId, buyer)
  positionDuration.add(position.timings.duration)
  joinErrors.add(!check(position, { 'position 200': (r) => r.status === 200 }))

  sleep(1)
}

/**
 * k6 calls this at the end. It writes a second summary carrying the scenario name and each
 * threshold's verdict, so RESULTS_SUMMARY.md can be generated from raw output rather than
 * assembled by hand.
 */
export function handleSummary(data: K6Summary): Record<string, string> {
  return summaryFile('joins', data)
}
