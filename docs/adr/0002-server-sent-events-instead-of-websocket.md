# ADR 0002: Push seat state over Server-Sent Events, not WebSocket

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

Every open browser needs to see a seat change within a few hundred milliseconds. The traffic is
almost entirely one-directional: the server pushes seat deltas, and the client's writes (hold,
confirm, cancel) are ordinary request/response operations that want normal HTTP semantics —
status codes, `Idempotency-Key`, `Retry-After`, tracing headers.

The target is at least 5,000 concurrent connections against three replicas, through a reverse
proxy, with a measurable propagation percentile.

## Decision

Seat state is pushed over Server-Sent Events on `GET /api/events/{eventId}/stream`. Writes stay
on ordinary REST endpoints.

## Alternatives considered

- **WebSocket.** Rejected. It buys bidirectional framing the product does not need, and costs:
  a second set of error semantics parallel to HTTP, manual reconnect and backoff, no built-in
  `Last-Event-ID` resume, and proxies that need explicit upgrade handling. The one thing it
  would add — client-to-server streaming — has no use here.
- **Long polling.** Rejected on cost at 5,000 connections: a reconnect per message multiplies
  request count by the delta rate, and the reconnect gap is exactly where a client misses an
  update.
- **Client polling every second.** Rejected: 5,000 clients polling 1 Hz is 5,000 req/s of pure
  overhead competing with the hold path for the connection pool, and still shows stale state
  for up to a second.

## Consequences

- The browser's `EventSource` gives automatic reconnect and `Last-Event-ID` for free, so the
  resync protocol in `docs/realtime-protocol.md` is a sequence-number check rather than a
  bespoke transport.
- HTTP/1.1 limits a browser to six connections per origin, so the client must hold exactly one
  stream per tab and multiplex all event subscriptions over it.
- Every proxy in the path must disable response buffering for the stream prefix or propagation
  latency becomes buffer-flush latency. This is configured in `infra/edge/nginx.conf` and is
  the first thing to check when a propagation measurement looks wrong.
- Connections are long-lived, so the server runs on virtual threads; a platform thread per
  connection would cap a replica far below the target.

## Falsifier

The SSE load script in `load/scripts/sse-fanout.js` opens the target connection count and
records propagation percentiles into `load/results/`. If the transport cannot hold 5,000
connections on three replicas, or propagation exceeds the documented budget, the committed raw
result says so. `docs/realtime-protocol.md` also defines a gap-injection case that the E2E
suite exercises: if `Last-Event-ID` resume does not work, that test fails.
