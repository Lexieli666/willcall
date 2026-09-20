import type {
  DeltaMessage,
  QueueFrame,
  ResyncMessage,
  SeatChange,
  SeatCounts,
  SnapshotMessage,
} from './seatTypes'

/**
 * The client side of docs/realtime-protocol.md.
 *
 * <p>The interesting part is not receiving messages, it is noticing what did not arrive. Sequence
 * numbers are contiguous per event, so a client holding 4712 that receives 4714 knows exactly one
 * message is missing and can say so, rather than quietly rendering a seat map that is wrong.
 *
 * Three independent safety nets, because any one of them can be defeated:
 *
 *  1. **Sequence cursor** — a gap triggers a resync.
 *  2. **Per-seat version** — a change is applied only if its version exceeds the one held, so a
 *     duplicated or reordered delta is a no-op rather than a regression.
 *  3. **Heartbeat watchdog** — three missed heartbeats mean the connection is dead even when TCP
 *     has not noticed, which on a phone that has switched networks it often has not.
 */

export type StreamStatus = 'connecting' | 'live' | 'resyncing' | 'offline'

export interface SeatStreamCallbacks {
  onSnapshot: (message: SnapshotMessage) => void
  onDelta: (changes: SeatChange[], counts: SeatCounts | null, sequence: number) => void
  onResyncRequired: (reason: string) => void
  onStatusChange: (status: StreamStatus) => void
  /** The buyer's own place in the waiting room. Outside the seat sequence entirely. */
  onQueue?: (frame: QueueFrame) => void
}

export interface SeatStreamOptions {
  /** The buyer this stream belongs to, so the waiting room can push their position. */
  userRef?: string
  /** Milliseconds without a heartbeat or message before the connection is treated as dead. */
  livenessTimeoutMs?: number
  /** Overridable so tests do not have to wait real seconds. */
  reconnectBaseMs?: number
  reconnectMaxMs?: number
  /** Injected in tests; defaults to the browser's EventSource. */
  eventSourceFactory?: (url: string) => EventSource
  now?: () => number
}

const DEFAULTS = {
  livenessTimeoutMs: 45_000,
  reconnectBaseMs: 1_000,
  reconnectMaxMs: 30_000,
}

export class SeatStream {
  private readonly eventId: string
  private readonly userRef: string | undefined
  private readonly callbacks: SeatStreamCallbacks
  private readonly options: Required<
    Omit<SeatStreamOptions, 'eventSourceFactory' | 'now' | 'userRef'>
  > & {
    eventSourceFactory: (url: string) => EventSource
    now: () => number
  }

  private source: EventSource | null = null
  private cursor: number | null = null
  private reconnectAttempts = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private livenessTimer: ReturnType<typeof setInterval> | null = null
  private lastMessageAt = 0
  private stopped = false

  /** Counters the diagnostics panel and the e2e tests read. */
  readonly stats = {
    snapshots: 0,
    deltas: 0,
    gaps: 0,
    resyncs: 0,
    reconnects: 0,
    staleDrops: 0,
    queueFrames: 0,
  }

  constructor(eventId: string, callbacks: SeatStreamCallbacks, options: SeatStreamOptions = {}) {
    this.eventId = eventId
    this.userRef = options.userRef
    this.callbacks = callbacks
    this.options = {
      livenessTimeoutMs: options.livenessTimeoutMs ?? DEFAULTS.livenessTimeoutMs,
      reconnectBaseMs: options.reconnectBaseMs ?? DEFAULTS.reconnectBaseMs,
      reconnectMaxMs: options.reconnectMaxMs ?? DEFAULTS.reconnectMaxMs,
      eventSourceFactory: options.eventSourceFactory ?? ((url) => new EventSource(url)),
      now: options.now ?? (() => Date.now()),
    }
  }

  start(): void {
    this.stopped = false
    this.connect()
    this.livenessTimer = setInterval(() => this.checkLiveness(), 5_000)
  }

  stop(): void {
    this.stopped = true
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    if (this.livenessTimer) clearInterval(this.livenessTimer)
    this.reconnectTimer = null
    this.livenessTimer = null
    this.closeSource()
  }

  /** The sequence number the client currently believes it is at. */
  get sequence(): number | null {
    return this.cursor
  }

  private closeSource(): void {
    if (this.source) {
      this.source.close()
      this.source = null
    }
  }

  private connect(): void {
    if (this.stopped) return
    this.closeSource()
    this.callbacks.onStatusChange(this.cursor === null ? 'connecting' : 'resyncing')

    // The cursor is passed as a query parameter as well as relying on EventSource's own
    // Last-Event-ID header: the header is only sent on an automatic reconnect, and this class
    // reconnects deliberately.
    // The buyer reference goes in the query string because EventSource cannot set headers. The
    // server needs it to push this buyer their own queue position.
    const params = new URLSearchParams()
    if (this.cursor !== null) params.set('lastEventId', String(this.cursor))
    if (this.userRef) params.set('userRef', this.userRef)
    const query = params.toString()
    const url = `/api/events/${this.eventId}/stream${query ? `?${query}` : ''}`

    const source = this.options.eventSourceFactory(url)
    this.source = source
    this.lastMessageAt = this.options.now()

    source.addEventListener('open', () => {
      this.reconnectAttempts = 0
      this.lastMessageAt = this.options.now()
      this.callbacks.onStatusChange('live')
    })

    source.addEventListener('snapshot', (event) => {
      this.lastMessageAt = this.options.now()
      const message = JSON.parse((event as MessageEvent<string>).data) as SnapshotMessage
      this.cursor = message.sequence
      this.stats.snapshots += 1
      this.callbacks.onSnapshot(message)
      this.callbacks.onStatusChange('live')
    })

    source.addEventListener('delta', (event) => {
      this.lastMessageAt = this.options.now()
      const message = JSON.parse((event as MessageEvent<string>).data) as DeltaMessage
      this.applyDelta(message)
    })

    source.addEventListener('queue', (event) => {
      this.lastMessageAt = this.options.now()
      // Queue frames carry no sequence id and must not touch the cursor. They are personal to
      // this buyer, so numbering them would make every other buyer's update look to this client
      // like a lost seat delta.
      const frame = JSON.parse((event as MessageEvent<string>).data) as QueueFrame
      this.stats.queueFrames += 1
      this.callbacks.onQueue?.(frame)
    })

    source.addEventListener('resync', (event) => {
      this.lastMessageAt = this.options.now()
      const message = JSON.parse((event as MessageEvent<string>).data) as ResyncMessage
      this.stats.resyncs += 1
      this.resync(message.reason)
    })

    source.addEventListener('error', () => {
      // EventSource reports both a transient blip and a dead server this way, so the only safe
      // reaction is to back off and try again.
      this.callbacks.onStatusChange('offline')
      this.scheduleReconnect()
    })
  }

  private applyDelta(message: DeltaMessage): void {
    if (this.cursor === null) {
      // A delta before any snapshot: the server thinks we have history we do not.
      this.stats.gaps += 1
      this.resync('delta_before_snapshot')
      return
    }

    if (message.sequence <= this.cursor) {
      this.stats.staleDrops += 1
      return
    }

    // A coalesced frame covers a range, not a point. Checking only `sequence` here would report
    // a gap for every multi-seat hold, because three seats consume three sequence numbers and
    // arrive as one frame. `fromSequence` is what makes coalescing and gap detection compatible.
    // Older servers do not send it; falling back to `sequence` keeps a single-change frame
    // correct rather than failing closed on a field that may be absent.
    const from = message.fromSequence ?? message.sequence
    if (from !== this.cursor + 1) {
      this.stats.gaps += 1
      this.resync(`gap_${this.cursor}_to_${from}`)
      return
    }

    this.cursor = message.sequence
    this.stats.deltas += 1
    this.callbacks.onDelta(message.changes, message.counts, message.sequence)
  }

  private resync(reason: string): void {
    this.callbacks.onStatusChange('resyncing')
    this.cursor = null
    this.callbacks.onResyncRequired(reason)
    this.closeSource()
    this.scheduleReconnect(0)
  }

  private scheduleReconnect(delayOverrideMs?: number): void {
    if (this.stopped || this.reconnectTimer) return

    // Jitter is not optional. Five thousand clients reconnecting on the same schedule after a
    // blip is a self-inflicted outage, and it arrives exactly when the service is least able to
    // absorb it.
    const backoff = Math.min(
      this.options.reconnectMaxMs,
      this.options.reconnectBaseMs * 2 ** this.reconnectAttempts,
    )
    const jittered = backoff * (0.8 + Math.random() * 0.4)
    const delay = delayOverrideMs ?? jittered

    this.reconnectAttempts += 1
    this.stats.reconnects += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  private checkLiveness(): void {
    if (this.stopped || !this.source) return
    if (this.options.now() - this.lastMessageAt < this.options.livenessTimeoutMs) return
    // Nothing at all for three heartbeat intervals. TCP may still believe the socket is fine.
    this.callbacks.onStatusChange('offline')
    this.closeSource()
    this.scheduleReconnect()
  }
}
