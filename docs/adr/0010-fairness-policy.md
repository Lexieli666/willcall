# ADR 0010: Fairness is arrival order, paced by measured capacity

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

Ten thousand people press the button at 10:00:00. Five thousand seats exist. Whatever happens
next, most of them lose — so the only thing the system can offer is that losing was fair, and that
they found out quickly.

Doing nothing is a policy, and a bad one: with no queue, admission order is decided by network
latency and client CPU. The fastest fibre connection and the newest phone win. That is not
neutral; it is a policy that favours people who already have things.

## Decision

**Arrival order, recorded once, at the edge.**

1. A buyer joins a Redis sorted set scored by the server's arrival timestamp. The score is set
   once and never changes: a refresh, a reconnect or a second tab does not move them, and
   duplicate joins return the existing position rather than a new one.
2. Admission is paced by a **token bucket whose rate is the measured downstream capacity**, not a
   guess. The reservation core is never handed more traffic than it has been measured to survive,
   which is what keeps the p99 inside its budget instead of admitting everyone and letting the
   database sort it out.
3. Position and an estimate are pushed over the same stream the seat map uses, so a person can see
   the queue moving rather than watching a spinner.
4. **People below the line are told so.** When the queue is longer than the remaining inventory,
   the ones who cannot possibly be served are shown that, rather than being left to queue for
   nothing. This is the part most systems get wrong, and it costs nothing to get right.

**FIFO is a target, not a guarantee, and the violation rate is published.** Admission happens
across replicas in batches, so two people who joined a millisecond apart can be admitted in the
other order. The measured inversion rate goes in the results, whatever it is. A claim of "strict
FIFO" would be a claim about something the architecture does not provide.

## Alternatives considered

- **No queue; first request wins.** Rejected: that is a policy favouring fast connections, chosen
  by omission.
- **A lottery among everyone who arrives in the first N seconds.** Genuinely fairer in one sense —
  it removes the advantage of arriving one second earlier — and it is what some ticketing systems
  do. Rejected here because it requires a fixed entry window, which changes the product from "sale
  opens now" into "registration opens now", and because it makes the queue position meaningless as
  feedback.
- **Strict FIFO with a global lock on admission.** Rejected: it serialises admission across
  replicas, which caps throughput at exactly the moment throughput matters, in exchange for a
  guarantee nobody can perceive at millisecond scale.
- **Weighted by account age or loyalty tier.** Out of scope, and a different product.

## Consequences

- Redis holds the queue, so losing Redis degrades it. The defined behaviour is open admission —
  the sale continues, unpaced — because refusing to sell tickets because the *queue* is down is
  worse than selling them unfairly for a few minutes. This is the one place where losing Redis is
  visible to buyers.
- The admission rate depends on a measured capacity figure, so that figure has to be re-measured
  whenever the reservation path changes. It lives in `docs/capacity-model.md` with its raw file.
- A per-buyer cap on active holds is part of fairness, not a separate anti-abuse feature: without
  it, one admitted buyer can hold the whole front row while deciding.

## Falsifier

The flash-sale k6 scenario records each virtual user's join time and admission time and computes
the inversion rate — the proportion of pairs admitted out of arrival order — into
`load/results/`. If admission is not close to arrival order, that number says so rather than the
README claiming otherwise. The rate-limit scenario asserts `429` with `Retry-After` rather than
dropped connections, and the Redis-restart test asserts the sale keeps working while the queue is
down.
