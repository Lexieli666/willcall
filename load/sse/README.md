# SSE load generator

k6 drives every HTTP scenario in this project. It does not drive this one, for a concrete reason:
k6 has no streaming response body, so it cannot hold a Server-Sent Events connection open and read
frames from it. Polling in a loop would measure something else entirely.

So the SSE scenario is a small Node program instead. It is written in TypeScript, typechecked in
CI alongside the k6 scripts, and run through `scripts/run-sse-load.sh`, which writes the same
`run-context.md` every other result directory has.

## What it measures

**Propagation latency, from commit to `onmessage`.** Every delta carries `committedAt`, stamped by
PostgreSQL when the transaction that changed the seat committed. The client subtracts that from its
own clock on receipt. Generator and service run on the same host, so the two clocks are the same
clock and the subtraction is exact — a caveat worth stating, because across machines it would not
be, and the figure would need NTP-quality clocks or a round-trip estimate instead.

**The figure includes the 50 ms coalescing window and the outbox relay tick, on purpose.** Both are
time a buyer waits. Excluding them would make the number smaller and less true.

**Connections actually established**, not connections attempted. A run that asks for 5,000 and gets
4,200 reports 4,200.

**Memory per connection**, as the difference in theservice's resident set with and without the
connections, divided by the count. Attributing memory to a connection any more precisely than that
would require a heap dump per connection, and the difference is what the capacity model needs.

## Running it

```bash
make up                       # three replicas behind the edge proxy
./scripts/run-sse-load.sh 5000
```

Tuning the host to allow thousands of sockets is documented in `docs/load-testing.md`. Without it
the run fails at around 1,000 connections with `EMFILE`, which is the file-descriptor limit and not
a limit of the service.
