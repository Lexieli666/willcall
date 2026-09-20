import http from 'k6/http'
import type { RefinedResponse, ResponseType } from 'k6/http'

/** Base URL of the system under test, injected by scripts/run-load.sh. */
export const BASE_URL: string = __ENV.BASE_URL || 'http://127.0.0.1:8080'

/** Directory the runner created for this run's artefacts. */
export const OUT_DIR: string = __ENV.SCENARIO_OUT || '.'

export interface SystemStatus {
  status: string
  postgres: string
  redis: string
  replica: string
  version: string
  commit: string
}

export function json<T>(response: RefinedResponse<ResponseType | undefined>): T | null {
  try {
    return response.json() as T
  } catch {
    return null
  }
}

export function systemStatus(): SystemStatus | null {
  return json<SystemStatus>(http.get(`${BASE_URL}/api/system/status`, { tags: { endpoint: 'system_status' } }))
}

/**
 * A v4 UUID good enough for an idempotency key. k6 has no crypto.randomUUID in every runtime,
 * and the keys only need to be unique within a run.
 */
export function uuid(): string {
  const hex = '0123456789abcdef'
  let out = ''
  for (let i = 0; i < 36; i += 1) {
    if (i === 8 || i === 13 || i === 18 || i === 23) out += '-'
    else if (i === 14) out += '4'
    else if (i === 19) out += hex[(Math.random() * 4) | 8] as string
    else out += hex[(Math.random() * 16) | 0] as string
  }
  return out
}
