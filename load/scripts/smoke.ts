// Smoke scenario: does the stack answer at all, and are the basics within an order of
// magnitude of the budget? It runs in CI on every push, so it is deliberately short and its
// thresholds are loose enough that only a real regression trips them. Capacity is measured by
// the flash and hold scenarios, not here.
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Rate } from 'k6/metrics'
import type { Options } from 'k6/options'
import { BASE_URL, json, type SystemStatus } from './lib/common.ts'

const errorRate = new Rate('willcall_errors')

export const options: Options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: Number(__ENV.SMOKE_VUS || 10),
      duration: __ENV.SMOKE_DURATION || '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<1000'],
    willcall_errors: ['rate<0.01'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export default function smoke(): void {
  const status = http.get(`${BASE_URL}/api/system/status`, { tags: { endpoint: 'system_status' } })
  const ok = check(status, {
    'status 200': (r) => r.status === 200,
    'postgres is up': (r) => json<SystemStatus>(r)?.postgres === 'UP',
  })
  errorRate.add(!ok)

  const ready = http.get(`${BASE_URL}/ready`, { tags: { endpoint: 'ready' } })
  errorRate.add(!check(ready, { 'ready 200': (r) => r.status === 200 }))

  sleep(1)
}
