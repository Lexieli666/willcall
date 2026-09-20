import { writeFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { Percentiles } from './stats.ts'
import { SseConnection, type ClientCounters } from './sseClient.ts'

/**
 * Opens N Server-Sent Events connections, drives seat changes through the service, and reports
 * how long each change took to reach a browser.
 *
 * Environment:
 *   BASE_URL        the edge proxy, default http://127.0.0.1:8080
 *   EVENT_ID        an existing event; one is created when absent
 *   CONNECTIONS     how many streams to open, default 5000
 *   RAMP_MS         how long to spread the connection opens over, default 20000
 *   HOLD_SECONDS    how long to keep them open and churning seats, default 60
 *   CHANGES_PER_SEC seat changes per second to generate, default 20
 *   OUT             where to write the JSON result
 */
const BASE_URL = process.env.BASE_URL ?? 'http://127.0.0.1:8080'
const CONNECTIONS = Number(process.env.CONNECTIONS ?? 5000)
const RAMP_MS = Number(process.env.RAMP_MS ?? 20_000)
const HOLD_SECONDS = Number(process.env.HOLD_SECONDS ?? 60)
const CHANGES_PER_SEC = Number(process.env.CHANGES_PER_SEC ?? 20)
const OUT = process.env.OUT ?? 'sse-result.json'

const counters: ClientCounters = {
  connected: 0,
  failed: 0,
  snapshots: 0,
  deltas: 0,
  changes: 0,
  gaps: 0,
  resyncs: 0,
  disconnects: 0,
  heartbeats: 0,
}
const propagation = new Percentiles()
const errors = new Map<string, number>()

function recordError(message: string): void {
  // Grouped rather than logged: five thousand identical ECONNRESET lines tell you less than one
  // line saying it happened five thousand times.
  const key = message.slice(0, 120)
  errors.set(key, (errors.get(key) ?? 0) + 1)
}

async function createEvent(): Promise<{ id: string; capacity: number }> {
  const response = await fetch(`${BASE_URL}/api/events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      venueName: 'SSE Load Arena',
      eventName: `SSE Load ${new Date().toISOString()}`,
      holdTtlSeconds: 30,
      maxSeatsPerOrder: 4,
      status: 'ON_SALE',
      priceTiers: [{ name: 'Standard', amountCents: 3500, currency: 'USD' }],
      sections: [{ name: 'Floor', rowCount: 40, seatsPerRow: 50, priceTierName: 'Standard' }],
    }),
  })
  if (!response.ok) throw new Error(`could not create an event: ${response.status}`)
  return (await response.json()) as { id: string; capacity: number }
}

async function seatIds(eventId: string): Promise<string[]> {
  const response = await fetch(`${BASE_URL}/api/events/${eventId}/seats`)
  const body = (await response.json()) as {
    sections: Array<{ rows: Array<{ seats: Array<{ id: string; status: string }> }> }>
  }
  return body.sections
    .flatMap((section) => section.rows)
    .flatMap((row) => row.seats)
    .filter((seat) => seat.status === 'AVAILABLE')
    .map((seat) => seat.id)
}

const REPLICA_PORTS = [18081, 18082, 18083]

/**
 * Collects garbage on every replica and returns the heap they retain, summed.
 *
 * <p>The obvious memory measurement — resident set before and during — attributes everything that
 * grew to the connections, including the garbage from delivering millions of messages. The first
 * 5,000-connection run reported 212 KiB per connection that way, most of which was throughput.
 * Collecting first, and comparing the heap with connections open against the heap after they
 * close, isolates what a connection actually costs.
 */
async function retainedHeapBytes(): Promise<number> {
  let total = 0
  for (const port of REPLICA_PORTS) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/api/admin/test/gc`, { method: 'POST' })
      if (!response.ok) continue
      const body = (await response.json()) as { heapUsedBytes: number }
      total += body.heapUsedBytes
    } catch {
      // A replica that is not listening contributes nothing rather than failing the run.
    }
  }
  return total
}

/** The application's own view of how many streams it is holding, per replica. */
async function serverConnectionCounts(): Promise<Record<string, number>> {
  const counts: Record<string, number> = {}
  for (const port of REPLICA_PORTS) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/actuator/prometheus`)
      if (!response.ok) continue
      const text = await response.text()
      const match = /willcall_sse_open_connections\{[^}]*\}\s+([0-9.]+)/.exec(text)
      const replica = /replica="([^"]+)"/.exec(text)?.[1] ?? String(port)
      if (match?.[1]) counts[replica] = Number(match[1])
    } catch {
      // A replica that is not listening is not an error here; it is reported by its absence.
    }
  }
  return counts
}

async function main(): Promise<void> {
  const eventId = process.env.EVENT_ID ?? (await createEvent()).id
  const seats = await seatIds(eventId)
  if (seats.length === 0) throw new Error('the event has no available seats')

  console.log(`event ${eventId} with ${seats.length} available seats`)
  console.log(`opening ${CONNECTIONS} connections over ${RAMP_MS} ms`)

  const before = await serverConnectionCounts()
  console.log(`server-side connections before: ${JSON.stringify(before)}`)

  const heapBefore = await retainedHeapBytes()
  console.log(`retained heap before: ${(heapBefore / 1024 / 1024).toFixed(1)} MiB`)

  const connections: SseConnection[] = []
  const running: Promise<void>[] = []
  const gapBetweenOpens = CONNECTIONS > 0 ? RAMP_MS / CONNECTIONS : 0

  for (let i = 0; i < CONNECTIONS; i++) {
    const connection = new SseConnection(
      `${BASE_URL}/api/events/${eventId}/stream?userRef=load-${i.toString().padStart(8, '0')}`,
      counters,
      propagation,
      recordError,
    )
    connections.push(connection)
    running.push(connection.start())
    if (gapBetweenOpens >= 1) await delay(gapBetweenOpens)
    else if (i % 50 === 0) await delay(1)
  }

  // Let the last connections settle and receive their snapshots before anything is measured.
  await delay(2_000)
  const peak = counters.connected
  const serverDuring = await serverConnectionCounts()
  console.log(`client-side connections established: ${peak}, failed: ${counters.failed}`)
  console.log(`server-side connections during: ${JSON.stringify(serverDuring)}`)

  // Drive seat changes and let the propagation measurements accumulate.
  const deadline = Date.now() + HOLD_SECONDS * 1000
  let changeIndex = 0
  let driven = 0
  while (Date.now() < deadline && changeIndex < seats.length) {
    const batch: Promise<unknown>[] = []
    for (let i = 0; i < CHANGES_PER_SEC && changeIndex < seats.length; i++) {
      const seatId = seats[changeIndex++] as string
      batch.push(
        fetch(`${BASE_URL}/api/events/${eventId}/holds`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Willcall-User': `driver-${changeIndex.toString().padStart(8, '0')}`,
            'Idempotency-Key': `sse-${changeIndex}-${Date.now()}`,
          },
          body: JSON.stringify({ seatIds: [seatId] }),
        })
          .then((response) => {
            if (response.ok) driven += 1
            return response.text()
          })
          .catch((cause: unknown) => recordError(`driver: ${String(cause)}`)),
      )
    }
    await Promise.all(batch)
    await delay(1_000)
  }

  // Everything in flight has had a second to arrive.
  await delay(2_000)

  // Heap retained while the connections are still open.
  const heapWithConnections = await retainedHeapBytes()
  console.log(`retained heap with ${peak} connections: ${(heapWithConnections / 1024 / 1024).toFixed(1)} MiB`)

  const summary = propagation.summary()
  const result = {
    baseUrl: BASE_URL,
    eventId,
    requestedConnections: CONNECTIONS,
    establishedConnections: peak,
    failedConnections: counters.failed,
    serverConnectionsBefore: before,
    serverConnectionsDuring: serverDuring,
    seatChangesDriven: driven,
    holdSeconds: HOLD_SECONDS,
    counters: { ...counters },
    propagationMs: summary,
    errors: Object.fromEntries(errors),
  }

  writeFileSync(OUT, JSON.stringify(result, null, 2))

  console.log('')
  console.log(`connections established : ${peak} of ${CONNECTIONS} requested`)
  console.log(`seat changes driven     : ${driven}`)
  console.log(`delta frames received   : ${counters.deltas} (${counters.changes} seat changes)`)
  console.log(`gaps detected           : ${counters.gaps}`)
  console.log(`resyncs requested       : ${counters.resyncs}`)
  console.log(
    `propagation commit→client (ms): n=${summary.count} p50=${summary.p50} p90=${summary.p90} ` +
      `p95=${summary.p95} p99=${summary.p99} max=${summary.max}`,
  )
  if (errors.size > 0) console.log(`errors: ${JSON.stringify(Object.fromEntries(errors))}`)
  console.log(`written to ${OUT}`)

  for (const connection of connections) connection.stop()
  await Promise.allSettled(running)

  // And again with them gone. The difference is what a connection costs.
  await delay(5_000)
  const heapAfterClose = await retainedHeapBytes()
  const retainedPerConnection = peak > 0 ? (heapWithConnections - heapAfterClose) / peak : null

  const withMemory = {
    ...result,
    retainedHeap: {
      beforeBytes: heapBefore,
      withConnectionsBytes: heapWithConnections,
      afterCloseBytes: heapAfterClose,
      perConnectionBytes: retainedPerConnection === null ? null : Math.round(retainedPerConnection),
      method:
        'Heap retained after an explicit collection, with the connections open, minus the same ' +
        'figure after they were closed, divided by the number established. This isolates what a ' +
        'connection costs from the garbage produced by delivering the messages. System.gc() is a ' +
        'request rather than a command, so it remains an estimate - but an estimate of the right ' +
        'quantity, unlike a resident-set delta taken during the run.',
    },
  }
  writeFileSync(OUT, JSON.stringify(withMemory, null, 2))

  if (retainedPerConnection !== null) {
    console.log(
      `retained heap after close: ${(heapAfterClose / 1024 / 1024).toFixed(1)} MiB` +
        ` → ${(retainedPerConnection / 1024).toFixed(1)} KiB retained per connection`,
    )
  }
}

main().catch((cause: unknown) => {
  console.error(cause)
  process.exit(1)
})
