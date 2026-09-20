# Willcall real-time protocol

**Written before the implementation.** The point of writing it first is that the client's recovery
behaviour is the hard part, and designing it after the server exists produces a protocol shaped by
whatever the server happened to do.

Transport: Server-Sent Events over HTTP/1.1. Why not WebSocket:
[ADR 0002](adr/0002-server-sent-events-instead-of-websocket.md).

---

## 1. The guarantee, and the three things that are not guaranteed

**Guaranteed:** a client that stays connected and applies every message it receives converges on
the server's view of the seat map, and knows when it has not.

**Not guaranteed, deliberately:**

1. **No exactly-once delivery.** A delta may arrive twice. Every delta carries the seat's version,
   and a client applies a delta only if the version is higher than the one it holds, so duplicates
   are free to ignore.
2. **No ordering across events.** Sequence numbers are per event. A client subscribed to two
   events tracks two cursors.
3. **No unbounded history.** A client that falls far enough behind is told to resync rather than
   being sent the backlog. Replaying an hour of deltas to a client that has been asleep costs more
   than sending it the current map.

## 2. Connecting

```
GET /api/events/{eventId}/stream
Accept: text/event-stream
Last-Event-ID: 4711            (optional; sent automatically by EventSource on reconnect)
```

Response headers:

```
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no
Connection: keep-alive
```

`X-Accel-Buffering: no` and `no-transform` are there because an intermediary that buffers the
stream turns the coalescing window into buffer-flush latency. The local edge proxy also sets
`proxy_buffering off` for this path; the header covers proxies this repository does not configure.

## 3. Message types

Every message is an SSE event with a `event:` name, a JSON `data:` payload, and — except for
heartbeats — an `id:` carrying the sequence number.

### 3.1 `snapshot` — always the first message

```
event: snapshot
id: 4711
data: {
  "eventId": "…",
  "sequence": 4711,
  "serverTime": "2026-09-20T18:31:00Z",
  "coalesceWindowMs": 50,
  "seats": [ {"id": "…", "status": "SOLD", "version": 12}, … ],
  "counts": {"available": 3821, "held": 94, "sold": 1085}
}
```

The snapshot is the complete seat state at sequence 4711. Everything after it is a delta relative
to that point. A client that reconnects with `Last-Event-ID: 4711` and finds the server still has
the intervening history gets deltas instead; otherwise it gets a fresh snapshot and discards what
it had.

### 3.2 `delta` — a batch of seat changes

```
event: delta
id: 4714
data: {
  "fromSequence": 4712,
  "sequence": 4714,
  "changes": [ {"id": "…", "status": "HELD", "version": 13, "committedAt": "2026-09-20T18:31:00.412Z"}, … ],
  "counts": {"available": 3820, "held": 95, "sold": 1085}
}
```

**`fromSequence` is what makes coalescing and gap detection compatible**, and its absence was a
real bug. A buyer holding three seats produces three seat changes, which consume three sequence
numbers, and the coalescing window sends them as one frame. With only a single `sequence` field
that frame carries the highest number, and a client holding 4711 that receives a frame stamped
4714 correctly concludes two messages are missing — on every multi-seat hold, forever. The frame
therefore carries the range it accounts for, and the client checks the low end.

One delta message may carry many seat changes: that is the coalescing window at work, not a
convenience.

`committedAt` is when the transaction that changed the seat committed, stamped by PostgreSQL.
It exists so propagation can be measured from the commit to the browser, which is what the budget
in section 5 is about. Measuring from when the fan-out picked the change up would quietly exclude
the relay's own queueing — time a buyer genuinely waits — and would make every published
percentile smaller than the truth.

`counts` is **nullable on a delta** and always present on a snapshot. The server sends it when it
has it cheaply and sends `null` otherwise; when it is null the client recomputes the totals from
the map it already holds. That is not a shortcut — it is exact, because the client holds every
seat, and it means the headline "1,412 seats left" is derived from the same data the map is drawn
from and cannot drift from it. Computing the totals server-side on every flush would cost a
database query per event per 50 ms window to produce a number the client can already derive.

### 3.3 `resync` — the server is telling the client to start over

```
event: resync
id: 4899
data: {"reason": "gap_too_large", "sequence": 4899}
```

Sent when the client's `Last-Event-ID` is older than the retained history, when the server's
per-connection buffer overflows, or after a replica restart with no shared history. The client
must re-fetch the seat map and reconnect without `Last-Event-ID`.

### 3.4 `queue` — this buyer's place in the waiting room

```
event: queue
data: {
  "state": "WAITING",
  "position": 412,
  "queueLength": 9310,
  "estimatedWaitSeconds": 21,
  "beyondInventory": false,
  "admissionToken": null,
  "serverTime": "2026-09-20T18:31:02Z"
}
```

**Carries no `id:` and is outside the seat sequence entirely.** Queue position is personal, so it
cannot ride the coalescer, which exists to send one identical frame to everybody. Giving these
frames a sequence number would corrupt the client's seat cursor, because the numbers would not be
contiguous from that client's point of view — every other buyer's position update would look like
a lost seat delta.

`state` is `WAITING`, `ADMITTED` or `DEGRADED_OPEN`. On `ADMITTED` the frame carries the signed
`admissionToken` the buyer then sends as `X-Willcall-Admission` when holding seats.

`beyondInventory` is true when more people are ahead than there are seats left. Showing it is the
difference between a queue and a waiting trap.

The frame is pushed every two seconds rather than polled. Ten thousand people asking for their
position every two seconds is five thousand requests per second of pure overhead competing with
the hold path for the same connection pool, at exactly the moment the hold path is busiest.

### 3.5 `:heartbeat` — an SSE comment, every 15 seconds

```
:heartbeat 2026-09-20T18:31:15Z
```

A comment, so `EventSource` ignores it and no client code runs. It exists to stop proxies and
load balancers reaping an idle connection, and to give the client a liveness signal: three missed
heartbeats means the connection is dead even if TCP has not noticed.

## 4. Sequence numbers and gap detection

Sequence numbers are **per event**, **monotonically increasing**, and **contiguous**. Contiguity
is what makes gap detection possible: a client holding 4712 that receives 4714 knows exactly one
message is missing, rather than having to guess.

They are assigned by the outbox relay under a per-event advisory lock, not by the writer. See
[ADR 0008](adr/0008-outbox-relay-assigns-the-sequence.md) for why: a counter written on the hold
path would serialise every acquisition for an event onto one row.

**Client algorithm:**

```
on snapshot(s):          cursor = s.sequence; replace the whole map
on delta(d):
    if d.sequence <= cursor:            ignore                  # duplicate
    else if d.fromSequence == cursor+1: apply; cursor = d.sequence
    else:                               resync()                # gap
on resync:                          resync()
on 3 missed heartbeats:             reconnect()
resync(): GET /api/events/{id}/seats, then reconnect without Last-Event-ID
```

Applying a delta is itself version-guarded: a change for a seat whose held version is already
higher is dropped. Between the sequence cursor and the per-seat version, no combination of
duplicate, out-of-order or replayed delivery can corrupt the client's map.

## 5. Coalescing

The server batches seat changes per event over a **50 ms window** before sending. Under a flash
sale, hundreds of seats change per second; sending one message per change would spend more time
on framing than on content, and no human perceives the difference.

**Every propagation number this project publishes includes this window.** A p99 of 180 ms means
180 ms from the database commit to the browser's `onmessage`, of which up to 50 ms is the server
deliberately waiting. Quoting propagation with the window excluded would be a 50 ms discount on a
number the reader cannot check.

The window is configurable (`willcall.realtime.coalesce-window-ms`) and its value is recorded in
every results file.

## 6. Cross-replica fan-out

A seat changes on the replica that served the request. Clients are spread across all replicas.

```
commit (seat + outbox, one transaction)
    → outbox relay on some replica assigns the sequence
    → PUBLISH willcall:event:{eventId} on Redis
    → every replica's subscriber receives it
    → each replica coalesces and writes to its own connections
```

Redis pub/sub is fire-and-forget: a message published while a replica is disconnected is lost to
that replica. That is acceptable and is why gap detection exists rather than being a
nice-to-have. The recovery path is the same one a client takes after a network blip.

**If Redis is down**, each replica still delivers changes it made itself, and clients on other
replicas detect the gap and resync against PostgreSQL. The queue degrades; correctness does not.
See [ADR 0001](adr/0001-postgresql-owns-correctness-redis-only-accelerates.md).

## 7. Backpressure

A slow client must not slow down a fast one, and must not consume unbounded server memory.

Each connection has a bounded outbound queue (default 64 messages). On overflow the server:

1. drops the queued deltas,
2. sends a single `resync` message,
3. resets the cursor.

Dropping to "resync required" rather than disconnecting keeps the connection — reconnect storms
are worse than resync storms — and rather than blocking, because blocking on one slow client is
how one bad mobile connection becomes everyone's latency.

## 8. Limits

| Limit | Value | Why |
|---|---|---|
| Streams per browser tab | 1 | HTTP/1.1 allows six connections per origin; one stream leaves five for everything else |
| Coalescing window | 50 ms | Section 5 |
| Heartbeat interval | 15 s | Below every default proxy idle timeout that matters |
| Outbound queue per connection | 64 messages | Section 7 |
| Retained history per event | 1 hour of published outbox rows | Beyond that a snapshot is cheaper than a replay |
| Reconnect backoff | 1 s, doubling to 30 s, ±20% jitter | Jitter is not optional: 5,000 clients reconnecting on the same schedule is a self-inflicted outage |

## 9. What would prove this document wrong

- **Gap detection:** an end-to-end test drops a delta on the way to the browser and asserts the
  client resyncs and ends with the correct map. If gap detection does not work, that test fails.
- **Propagation:** the SSE load script measures commit-to-`onmessage` at the target connection
  count and writes the percentiles to `load/results/`. If the coalescing window is not what this
  document says, the floor of the distribution shows it.
- **Backpressure:** a load-script variant reads slowly and asserts the server's memory per
  connection stays inside the published band and that the slow reader receives `resync` rather
  than stalling the others.
- **Redis loss:** an integration test restarts Redis mid-run and then runs the full invariant
  suite. If losing the fan-out can corrupt inventory, that test fails.
