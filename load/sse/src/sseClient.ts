import { Percentiles } from './stats.ts'

export interface SeatChange {
  id: string
  status: string
  version: number
  committedAt: string | null
}

export interface ClientCounters {
  connected: number
  failed: number
  snapshots: number
  deltas: number
  changes: number
  gaps: number
  resyncs: number
  disconnects: number
  heartbeats: number
}

/**
 * One Server-Sent Events connection, parsed by hand.
 *
 * <p>Node's `fetch` gives a `ReadableStream` body, which is what k6 cannot do and is the whole
 * reason this program exists. The frame parser is deliberately small: SSE is a line protocol, and
 * pulling in a library would add a dependency whose buffering behaviour would then be part of
 * every measurement.
 */
export class SseConnection {
  private controller = new AbortController()
  private cursor: number | null = null
  private buffer = ''

  constructor(
    private readonly url: string,
    private readonly counters: ClientCounters,
    private readonly propagation: Percentiles,
    private readonly onError: (message: string) => void,
  ) {}

  async start(): Promise<void> {
    let response: Response
    try {
      response = await fetch(this.url, {
        headers: { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' },
        signal: this.controller.signal,
      })
    } catch (cause) {
      this.counters.failed += 1
      this.onError(cause instanceof Error ? cause.message : String(cause))
      return
    }

    if (!response.ok || !response.body) {
      this.counters.failed += 1
      this.onError(`HTTP ${response.status}`)
      return
    }

    this.counters.connected += 1

    const reader = response.body.getReader()
    const decoder = new TextDecoder()

    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        this.buffer += decoder.decode(value, { stream: true })
        this.drainFrames()
      }
      this.counters.disconnects += 1
    } catch (cause) {
      if (!this.controller.signal.aborted) {
        this.counters.disconnects += 1
        this.onError(cause instanceof Error ? cause.message : String(cause))
      }
    } finally {
      this.counters.connected -= 1
    }
  }

  private drainFrames(): void {
    for (;;) {
      const boundary = this.buffer.indexOf('\n\n')
      if (boundary < 0) return
      const frame = this.buffer.slice(0, boundary)
      this.buffer = this.buffer.slice(boundary + 2)
      this.handleFrame(frame)
    }
  }

  private handleFrame(frame: string): void {
    // Timestamp first: anything after this is parsing cost attributed to the wrong thing.
    const receivedAt = Date.now()

    let name: string | null = null
    let data = ''
    for (const line of frame.split('\n')) {
      if (line.startsWith(':')) {
        this.counters.heartbeats += 1
        return
      }
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data += line.slice(5).trim()
    }
    if (!name || !data) return

    if (name === 'snapshot') {
      const message = JSON.parse(data) as { sequence: number }
      this.cursor = message.sequence
      this.counters.snapshots += 1
      return
    }

    if (name === 'resync') {
      this.counters.resyncs += 1
      this.cursor = null
      return
    }

    if (name !== 'delta') return

    const message = JSON.parse(data) as {
      fromSequence: number
      sequence: number
      changes: SeatChange[]
    }

    if (this.cursor !== null) {
      const from = message.fromSequence ?? message.sequence
      if (message.sequence <= this.cursor) return
      if (from !== this.cursor + 1) this.counters.gaps += 1
    }
    this.cursor = message.sequence
    this.counters.deltas += 1
    this.counters.changes += message.changes.length

    for (const change of message.changes) {
      if (!change.committedAt) continue
      // Commit to onmessage. Generator and service share a host, so the clocks are the same
      // clock; across machines this subtraction would need NTP-quality time and is noted as such
      // in the results.
      this.propagation.add(receivedAt - Date.parse(change.committedAt))
    }
  }

  stop(): void {
    this.controller.abort()
  }
}
