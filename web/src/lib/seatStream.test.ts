import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SeatStream, type SeatStreamCallbacks } from './seatStream'
import type { SeatChange, SeatCounts } from './seatTypes'

/**
 * A stand-in for EventSource that lets a test deliver exactly the frames it wants, including the
 * ones a real server would rather not send: a gap, a duplicate, and a delta before any snapshot.
 */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  readonly url: string
  private listeners = new Map<string, Array<(event: Event) => void>>()
  closed = false

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (event: Event) => void): void {
    const existing = this.listeners.get(type) ?? []
    existing.push(listener)
    this.listeners.set(type, existing)
  }

  close(): void {
    this.closed = true
  }

  emit(type: string, data?: unknown): void {
    const event =
      data === undefined ? new Event(type) : new MessageEvent(type, { data: JSON.stringify(data) })
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }
}

function makeStream(overrides: Partial<SeatStreamCallbacks> = {}) {
  const received: { changes: SeatChange[]; counts: SeatCounts | null; sequence: number }[] = []
  const resyncs: string[] = []
  const statuses: string[] = []
  const snapshots: number[] = []

  const callbacks: SeatStreamCallbacks = {
    onSnapshot: (message) => snapshots.push(message.sequence),
    onDelta: (changes, counts, sequence) => received.push({ changes, counts, sequence }),
    onResyncRequired: (reason) => resyncs.push(reason),
    onStatusChange: (status) => statuses.push(status),
    ...overrides,
  }

  const stream = new SeatStream('event-1', callbacks, {
    eventSourceFactory: (url) => new FakeEventSource(url) as unknown as EventSource,
    reconnectBaseMs: 10,
    reconnectMaxMs: 20,
  })

  return { stream, received, resyncs, statuses, snapshots }
}

function snapshot(sequence: number) {
  return {
    eventId: 'event-1',
    sequence,
    serverTime: '2026-09-20T00:00:00Z',
    coalesceWindowMs: 50,
    seats: [],
    counts: null,
  }
}

function delta(sequence: number, id = 'seat-1') {
  return {
    fromSequence: sequence,
    sequence,
    changes: [{ id, status: 'HELD' as const, version: sequence }],
    counts: null,
  }
}

/** A frame that coalesced several changes and therefore spans a range of sequence numbers. */
function coalescedDelta(fromSequence: number, sequence: number) {
  return {
    fromSequence,
    sequence,
    changes: Array.from({ length: sequence - fromSequence + 1 }, (_, i) => ({
      id: `seat-${i}`,
      status: 'HELD' as const,
      version: fromSequence + i,
    })),
    counts: null,
  }
}

beforeEach(() => {
  FakeEventSource.instances = []
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('SeatStream', () => {
  it('applies deltas that arrive in order', () => {
    const { stream, received } = makeStream()
    stream.start()
    const source = FakeEventSource.instances[0]!

    source.emit('snapshot', snapshot(10))
    source.emit('delta', delta(11))
    source.emit('delta', delta(12))

    expect(received.map((r) => r.sequence)).toEqual([11, 12])
    expect(stream.sequence).toBe(12)
    stream.stop()
  })

  it('accepts a coalesced frame spanning several sequence numbers without calling it a gap', () => {
    // Three seats held at once consume sequences 11, 12 and 13 and arrive as one frame. Checking
    // only the highest number would report a gap on every multi-seat hold, which is exactly the
    // bug an end-to-end test caught: the client reported gaps that had not happened.
    const { stream, received, resyncs } = makeStream()
    stream.start()
    const source = FakeEventSource.instances[0]!

    source.emit('snapshot', snapshot(10))
    source.emit('delta', coalescedDelta(11, 13))
    source.emit('delta', delta(14))

    expect(resyncs).toHaveLength(0)
    expect(received.map((r) => r.sequence)).toEqual([13, 14])
    expect(stream.sequence).toBe(14)
    stream.stop()
  })

  it('still detects a gap before a coalesced frame', () => {
    const { stream, resyncs } = makeStream()
    stream.start()
    const source = FakeEventSource.instances[0]!

    source.emit('snapshot', snapshot(10))
    source.emit('delta', coalescedDelta(13, 15)) // 11 and 12 never arrived

    expect(resyncs).toHaveLength(1)
    expect(resyncs[0]).toMatch(/gap_10_to_13/)
    stream.stop()
  })

  it('detects a gap and asks for a resync instead of rendering a wrong map', () => {
    const { stream, received, resyncs } = makeStream()
    stream.start()
    const source = FakeEventSource.instances[0]!

    source.emit('snapshot', snapshot(10))
    source.emit('delta', delta(11))
    source.emit('delta', delta(14)) // 12 and 13 never arrived

    expect(received.map((r) => r.sequence)).toEqual([11])
    expect(resyncs).toHaveLength(1)
    expect(resyncs[0]).toMatch(/gap_11_to_14/)
    expect(stream.stats.gaps).toBe(1)
    stream.stop()
  })

  it('drops a duplicate delta rather than applying it twice', () => {
    const { stream, received, resyncs } = makeStream()
    stream.start()
    const source = FakeEventSource.instances[0]!

    source.emit('snapshot', snapshot(10))
    source.emit('delta', delta(11))
    source.emit('delta', delta(11))

    expect(received).toHaveLength(1)
    expect(resyncs).toHaveLength(0)
    expect(stream.stats.staleDrops).toBe(1)
    stream.stop()
  })

  it('treats a delta arriving before any snapshot as a gap', () => {
    const { stream, resyncs } = makeStream()
    stream.start()
    const source = FakeEventSource.instances[0]!

    source.emit('delta', delta(5))

    expect(resyncs).toEqual(['delta_before_snapshot'])
    stream.stop()
  })

  it('reconnects without a cursor after a resync, so the server sends a fresh snapshot', () => {
    const { stream } = makeStream()
    stream.start()
    const first = FakeEventSource.instances[0]!
    expect(first.url).toBe('/api/events/event-1/stream')

    first.emit('snapshot', snapshot(10))
    first.emit('resync', { reason: 'slow_consumer', sequence: 10 })
    vi.advanceTimersByTime(5)

    const second = FakeEventSource.instances[1]!
    expect(second.url).toBe('/api/events/event-1/stream')
    expect(first.closed).toBe(true)
    stream.stop()
  })

  it('resumes from its cursor after a transient error', () => {
    const { stream } = makeStream()
    stream.start()
    const first = FakeEventSource.instances[0]!

    first.emit('snapshot', snapshot(10))
    first.emit('delta', delta(11))
    first.emit('error')
    vi.advanceTimersByTime(100)

    const second = FakeEventSource.instances[1]!
    expect(second.url).toBe('/api/events/event-1/stream?lastEventId=11')
    stream.stop()
  })

  it('backs off with jitter rather than hammering the server', () => {
    const { stream } = makeStream()
    stream.start()

    for (let attempt = 0; attempt < 4; attempt += 1) {
      const source = FakeEventSource.instances[FakeEventSource.instances.length - 1]!
      source.emit('error')
      vi.advanceTimersByTime(200)
    }

    // Five sources: the original plus one per reconnect. The point is that each error produced
    // exactly one reconnect, not a storm.
    expect(FakeEventSource.instances).toHaveLength(5)
    stream.stop()
  })

  it('gives up on a silent connection after the liveness timeout', () => {
    const { stream, statuses } = makeStream()
    stream.start()
    const source = FakeEventSource.instances[0]!
    source.emit('snapshot', snapshot(10))

    vi.advanceTimersByTime(60_000)

    expect(statuses).toContain('offline')
    expect(source.closed).toBe(true)
    stream.stop()
  })

  it('stops cleanly and does not reconnect afterwards', () => {
    const { stream } = makeStream()
    stream.start()
    const source = FakeEventSource.instances[0]!

    stream.stop()
    source.emit('error')
    vi.advanceTimersByTime(60_000)

    expect(FakeEventSource.instances).toHaveLength(1)
  })
})
