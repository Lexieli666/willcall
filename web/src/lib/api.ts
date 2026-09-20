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

// --------------------------------------------------------------------------- buyer identity

const USER_REF_KEY = 'willcall.userRef'

/**
 * The opaque buyer reference every reservation call is scoped to.
 *
 * <p>Generated once per browser and kept in localStorage so a reload does not lose an active
 * hold. It is not a login and not a secret: from Phase 3 the waiting room issues a signed
 * admission token and this becomes the fallback for a buyer who has not queued.
 *
 * <p>localStorage can throw — private mode, blocked site data — so every access is guarded and
 * an in-memory reference is used when it does. Losing the reference on reload is worse than
 * crashing only in the sense that it is silent, so the failure is at least confined here.
 */
let inMemoryUserRef: string | null = null

export function userRef(): string {
  if (inMemoryUserRef) return inMemoryUserRef
  try {
    const stored = localStorage.getItem(USER_REF_KEY)
    if (stored) {
      inMemoryUserRef = stored
      return stored
    }
  } catch {
    // Private mode or blocked storage; fall through and generate a per-session reference.
  }

  const generated = `buyer-${crypto.randomUUID().replace(/-/g, '').slice(0, 24)}`
  inMemoryUserRef = generated
  try {
    localStorage.setItem(USER_REF_KEY, generated)
  } catch {
    // Not fatal: the reference lives for this page's lifetime instead.
  }
  return generated
}

export function buyerHeaders(idempotencyKey?: string): Record<string, string> {
  const headers: Record<string, string> = { 'X-Willcall-User': userRef() }
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
  return headers
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}
