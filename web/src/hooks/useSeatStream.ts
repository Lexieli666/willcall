import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { SeatStream, type StreamStatus } from '../lib/seatStream'
import { SeatMapStore } from '../lib/seatMapStore'
import { fetchSeatMap } from '../lib/reservations'
import type { SeatCounts } from '../lib/seatTypes'

export interface SeatStreamState {
  store: SeatMapStore | null
  revision: number
  counts: SeatCounts | null
  status: StreamStatus
  sequence: number | null
  /** Counters the diagnostics panel and the end-to-end tests read. */
  stats: { snapshots: number; deltas: number; gaps: number; resyncs: number; reconnects: number }
  error: string | null
}

/**
 * Loads the seat map and keeps it live.
 *
 * <p>The order matters and is not arbitrary: the stream is opened <em>first</em> and the snapshot
 * that opens it is what populates the map. Fetching the map over REST and then subscribing would
 * leave a window between the two in which changes are lost silently — the bug that gap detection
 * exists to catch, introduced on purpose by the loading order.
 *
 * <p>A resync re-fetches the map over REST, because that is the one call guaranteed to return the
 * present rather than a position in a sequence.
 */
export function useSeatStream(eventId: string | undefined): SeatStreamState {
  const [store, setStore] = useState<SeatMapStore | null>(null)
  const [revision, setRevision] = useState(0)
  const [counts, setCounts] = useState<SeatCounts | null>(null)
  const [status, setStatus] = useState<StreamStatus>('connecting')
  const [sequence, setSequence] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [stats, setStats] = useState({ snapshots: 0, deltas: 0, gaps: 0, resyncs: 0, reconnects: 0 })

  const storeRef = useRef<SeatMapStore | null>(null)
  const streamRef = useRef<SeatStream | null>(null)

  const refreshStats = useCallback(() => {
    const stream = streamRef.current
    if (!stream) return
    setStats({
      snapshots: stream.stats.snapshots,
      deltas: stream.stats.deltas,
      gaps: stream.stats.gaps,
      resyncs: stream.stats.resyncs,
      reconnects: stream.stats.reconnects,
    })
  }, [])

  useEffect(() => {
    if (!eventId) return undefined

    let cancelled = false

    const loadMap = async () => {
      try {
        const map = await fetchSeatMap(eventId)
        if (cancelled) return
        // The mark the SeatMap component measures against. Set here, where the data actually
        // becomes available, so the measurement covers building and painting the map and not
        // the network.
        performance.mark('willcall:seatmap:data-ready')
        const next = new SeatMapStore(map)
        storeRef.current = next
        setStore(next)
        setCounts(next.counts)
        setSequence(next.sequence)
        setRevision((value) => value + 1)
        setError(null)
      } catch (cause) {
        if (!cancelled) setError(cause instanceof Error ? cause.message : 'could not load the seat map')
      }
    }

    const stream = new SeatStream(eventId, {
      onSnapshot: (message) => {
        // The snapshot carries the full state, but the REST map carries labels, prices and the
        // section/row structure that the stream deliberately does not repeat on every connect.
        // So: fetch the map once, then let the snapshot's sequence number set the cursor.
        if (!storeRef.current) void loadMap()
        setSequence(message.sequence)
        if (message.counts) setCounts(message.counts)
        refreshStats()
      },
      onDelta: (changes, deltaCounts, deltaSequence) => {
        const current = storeRef.current
        if (!current) return
        const dirty = current.apply(changes, deltaCounts, deltaSequence)
        if (dirty.length > 0) setRevision((value) => value + 1)
        setCounts({ ...current.counts })
        setSequence(deltaSequence)
        refreshStats()
      },
      onResyncRequired: () => {
        void loadMap()
        refreshStats()
      },
      onStatusChange: (next) => {
        setStatus(next)
        refreshStats()
      },
    })

    streamRef.current = stream
    void loadMap()
    stream.start()

    return () => {
      cancelled = true
      stream.stop()
      streamRef.current = null
    }
  }, [eventId, refreshStats])

  return useMemo(
    () => ({ store, revision, counts, status, sequence, stats, error }),
    [store, revision, counts, status, sequence, stats, error],
  )
}
