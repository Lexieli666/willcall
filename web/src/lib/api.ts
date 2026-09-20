/**
 * Thin fetch wrapper. It exists so that every call site gets the same error shape and so the
 * SSE and REST layers agree on the API origin, which differs between the Vite dev server
 * (proxied) and the production build (same origin behind the reverse proxy).
 */
export const API_BASE = '/api'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly retryAfterSeconds: number | null
  readonly detail: unknown

  constructor(status: number, code: string, message: string, retryAfterSeconds: number | null, detail: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.retryAfterSeconds = retryAfterSeconds
    this.detail = detail
  }
}

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  code?: string
  [key: string]: unknown
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    const retryAfterHeader = response.headers.get('Retry-After')
    let problem: ProblemDetail = {}
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      problem = { detail: response.statusText }
    }
    throw new ApiError(
      response.status,
      typeof problem.code === 'string' ? problem.code : `http_${response.status}`,
      typeof problem.detail === 'string' ? problem.detail : response.statusText,
      retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : null,
      problem,
    )
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export interface SystemStatus {
  status: string
  postgres: string
  redis: string
  replica: string
  version: string
  commit: string
}

export function fetchSystemStatus(): Promise<SystemStatus> {
  return apiFetch<SystemStatus>('/system/status')
}
