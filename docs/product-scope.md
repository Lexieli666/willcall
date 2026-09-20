# Product scope

## The problem

A venue puts 5,000 seats on sale at 10:00. At 10:00:00, 10,000 people press the button. Most of
them will not get a seat. The ones who do must actually get it — not a confirmation that is
later revoked — and the ones who do not must find out quickly and fairly, rather than watching
a spinner for forty seconds and then being told to try again.

That is the whole product: allocate a fixed, contested inventory under a burst, without
overselling, without lying to anyone, and without excluding people who use a keyboard or a
screen reader.

## Who it is for

- **Buyers** arriving in a flash crowd, most of them on a phone, many of them on a bad
  connection, some of them using assistive technology.
- **Organizers** who need to see, live, what is selling and what is stuck.

## What it does

1. **Waiting room.** Arrivals join a fair queue ordered by arrival time. They see their
   position and an estimate, updated live. Admission is paced by a token bucket tied to the
   measured downstream capacity, so the reservation core is never handed more traffic than it
   was measured to survive.
2. **Seat acquisition, two ways.** Pick exact seats from a map, or ask for *N* seats together
   and let the server find the best contiguous run.
3. **Holds with a visible timer.** A successful acquisition produces a hold with an expiry. The
   buyer sees the countdown. When it expires, the seats return to the pool exactly once.
4. **Idempotent checkout.** Checkout carries an `Idempotency-Key`. A retry — from a flaky
   network, an impatient double-tap, or an at-least-once client — returns the original outcome
   and never charges or allocates twice.
5. **Live seat state.** Every open browser sees a seat change within the propagation budget,
   whichever replica served the change.
6. **Organizer view.** Live inventory, holds outstanding, queue depth, sell-through.

## What a user sees when things go wrong

These are product decisions, and each is recorded as an ADR rather than being left to whatever
the code happened to do:

- **The seat you clicked was taken between render and click.** The seat is marked taken in
  place, the map updates around it, and the selection is not silently swapped for a different
  seat. See [ADR 0011](./adr/0011-losing-a-seat-mid-checkout.md).
- **Your hold expired while you were entering payment details.** The checkout form is
  disabled, the reason is announced to a screen reader as well as shown, and the buyer is
  offered the same seats again if they are still free. See
  [ADR 0011](./adr/0011-losing-a-seat-mid-checkout.md).
- **The queue is longer than the seats remaining.** People below the line are told so, rather
  than being left to queue for nothing. See [ADR 0010](./adr/0010-fairness-policy.md).
- **You are being rate limited.** `429` with `Retry-After`, and a UI that counts down rather
  than inviting an immediate retry.

## Non-goals

Stated here so that their absence reads as a decision rather than an omission:

- **Real payments.** A fake gateway stands in, configurable to succeed, fail, time out, or
  succeed *after* timing out — the last of which is the interesting one, and the reason
  checkout is idempotent.
- **Seller onboarding, search, recommendations.** Willcall sells an inventory it is given.
- **Email and SMS.** No notification channel.
- **Fraud and bot detection** beyond per-identity rate limits. A determined scripted buyer will
  do well here, as they do everywhere.
- **Native mobile.** Responsive web only.
- **Kafka, Kubernetes, microservice decomposition.** See
  [ADR 0003](./adr/0003-modular-monolith-not-microservices.md).

## How success is judged

Every claim in `README.md` is a measurement with a raw file behind it and a named test or job
that would fail if the claim stopped being true. The targets are in the specification; the
measured values, including the ones that missed, are in `load/RESULTS_SUMMARY.md`.
