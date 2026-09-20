# Accessibility

The claim this project makes is narrow and checkable: **a person can buy a seat using only a
keyboard, or only a screen reader, and CI blocks any merge that breaks that.**

Everything below is either enforced by a gate (with the gate named) or listed as an outstanding
manual check (with nothing claimed for it).

## What is enforced, and by what

| Requirement | Gate | Where |
|---|---|---|
| Zero axe violations on every route **and every state** | `expectNoAxeViolations` in the Playwright suite; merge blocked on failure | `web/e2e/*.spec.ts` |
| Lighthouse accessibility exactly 100 | `lighthouserc.cjs`, `categories:accessibility` minScore 1 | CI `e2e` job |
| Lighthouse desktop performance ≥ 90 | `categories:performance` minScore 0.9 | CI `e2e` job |
| A complete purchase using only key events | `keyboard.spec.ts`, which uses no `click()` at all | CI `e2e` job |
| 4.5:1 contrast on every text pair, both themes | `tokens.test.ts`, which reads `tokens.css` | CI `frontend` job |
| 3:1 on every non-text pair (borders, focus rings) | the same test | CI `frontend` job |
| Seat state never conveyed by colour alone | `SeatMap.test.tsx` asserts the glyph and the worded state | CI `frontend` job |
| Grid is one tab stop, not one per seat | `keyboard.spec.ts` counts `tabindex="0"` cells | CI `e2e` job |
| Live regions present from first paint | `LiveAnnouncer.test.tsx` | CI `frontend` job |
| Reduced motion honoured | a global `prefers-reduced-motion` block, so a new animation cannot forget it | `global.css` |

"Every state" is the part that is easy to skip. The suite scans the seat map empty, with a
selection, while holding, on the confirmation page, in text-list mode, and on the organizer and
status routes. A page that passes axe before anything happens and fails after the first
interaction is a page that passes the check and fails the user.

## Decisions that shaped the interface

**The seat map is real DOM, not canvas.** A canvas is one unlabelled element to a screen reader
and nothing at all to a keyboard. The cost is render time, which became a budget:
[ADR 0009](adr/0009-seat-map-as-real-dom.md).

**A roving tabindex, not a tab stop per seat.** Five thousand tab stops would take five thousand
presses to get past. The grid is one stop; arrow keys move inside it.

| Key | Moves |
|---|---|
| ← → | Previous / next seat in the row, stopping at the ends rather than wrapping into another row |
| ↑ ↓ | Same position in the row above / below, clamped to that row's length |
| Home / End | First / last seat in the row |
| Ctrl+Home / Ctrl+End | First / last seat in the map |
| PageUp / PageDown | Ten rows |
| Enter / Space | Select |

Not wrapping at a row's end is deliberate: wrapping silently moves a buyer to a different part of
the venue, and somebody who cannot see the map has no way to notice.

**Text-list mode is an alternative, not a fallback.** For "two seats together under $60" a filtered
list beats arrowing around a grid counting gaps. For "where in the venue am I sitting" the grid
wins. The toggle is a preference, and both are gated by axe.

**Announcements are rationed, and that is the whole design.** During a sell-out the map changes
dozens of times a second. A live region wired to that is unusable — a continuous stream of seat
numbers that cannot be interrupted, drowning out the messages that matter. So:

- **The buyer's own state** — hold acquired, hold expiring, a selected seat taken — is announced
  immediately and assertively. These are rare by construction: they concern one person's actions.
- **The room** — everyone else's seats — is never announced individually. It is summarised as a
  count, at most once every ten seconds, and only when the count has actually moved.

**Focus moves to what changed.** After a hold, a release or an expiry, focus goes to the "Your
selection" heading, because the button that was focused has been replaced. Leaving focus on a
removed element drops it to `<body>`, and a keyboard user then has to tab from the top of the page
to find out what happened.

**The countdown is spoken in words.** "2:05" is announced by most screen readers as a time of day.
The visible text stays "2:05"; a hidden live region says "2 minutes 5 seconds". It updates every
thirty seconds, then every ten under a minute, then every second under ten — a live region ticking
once a second makes the rest of the page inaudible.

**Colour is never the only signal.** Each seat state has a glyph (○ available, ◑ on hold, ● sold,
✕ not for sale) and a worded state in its accessible name. The map survives a colour-vision
deficiency, a greyscale print, and Windows high-contrast mode, where every author colour is
replaced — which the stylesheet handles explicitly with a `forced-colors` block.

## Measured

From `load/results/2026-09-20/phase2-frontend/`.

| Measurement | Result | Budget |
|---|---|---|
| Lighthouse accessibility (3 runs, desktop) | **100** | 100 |
| Lighthouse performance | **100** | ≥ 90 |
| Largest Contentful Paint | **445 ms** | < 1,500 ms |
| Cumulative Layout Shift | **0.0085** | < 0.05 |
| Total Blocking Time | **0 ms** | — |
| Gzipped JavaScript for the route | **100.1 KB** | < 180 KB |
| Axe violations across all routes and states | **0** | 0 |

Interaction to Next Paint is **not measured**. It is a field metric that needs real interactions
from real sessions, and Lighthouse's lab proxy for it is Total Blocking Time, which is reported
above as 0 ms. Claiming an INP figure from a lab run would be claiming a field measurement that
has not been taken.

---

## Outstanding: the manual passes

> **None of the following has been done. Nothing in this repository claims otherwise, and the
> corresponding resume bullets must not be used until each row is complete and its recording is
> linked here.**

Automated checks cover what a machine can decide: that a control has a name, that contrast is
sufficient, that focus is reachable. They cannot decide whether an announcement is *useful*,
whether the reading order makes sense, or whether somebody can actually complete a purchase
without becoming lost. That needs a person and a real screen reader.

| # | Check | Tool | Platform | Status | Evidence |
|---|---|---|---|---|---|
| 1 | Complete a purchase with the seat map, using NVDA only | NVDA + Firefox | Windows 11 | ☐ **not done** | — |
| 2 | Complete a purchase with text-list mode, using NVDA only | NVDA + Firefox | Windows 11 | ☐ **not done** | — |
| 3 | Hold expiry is noticed and understood while on the payment step | NVDA + Firefox | Windows 11 | ☐ **not done** | — |
| 4 | A selected seat being taken is noticed without looking | NVDA + Firefox | Windows 11 | ☐ **not done** | — |
| 5 | Complete a purchase with the seat map, using VoiceOver only | VoiceOver + Safari | macOS | ☐ **not done** | — |
| 6 | Complete a purchase with text-list mode, using VoiceOver only | VoiceOver + Safari | macOS | ☐ **not done** | — |
| 7 | Rotor navigation of the grid behaves sensibly | VoiceOver + Safari | macOS | ☐ **not done** | — |
| 8 | The waiting room's position updates are audible without being constant | both | both | ☐ **not done** | — |
| 9 | The whole purchase at 200% browser zoom | — | both | ☐ **not done** | — |
| 10 | The whole purchase in Windows high-contrast mode | — | Windows 11 | ☐ **not done** | — |

**Each row needs a screen recording with audio**, linked in the Evidence column. A tick without a
recording is an assertion, and this document does not accept assertions in place of evidence.

### What to watch for, for whoever runs these

The automated gates are silent about exactly these things, which is why they are worth a person's
time:

- **Is the announcement rate tolerable?** The ten-second ambient throttle is a guess. Ten seconds
  might be too often to think over, or too rare to feel informed. Only listening will say.
- **Does the grid's size overwhelm?** Announcing "row B, seat 3 of 8, row 2 of 4" on every arrow
  press is correct and may be exhausting across a hundred rows.
- **Is the countdown's urgency legible?** The visual design turns red at thirty seconds. The audio
  equivalent is the announcement changing from every ten seconds to every one. Whether that reads
  as urgency or as noise is a human judgement.
- **Does focus landing on "Your selection" make sense**, or does it feel like being teleported?
- **Is losing a seat mid-checkout survivable?** This is the worst moment in the product. Whether
  the assertive announcement arrives in time to stop somebody typing a card number is the single
  most important thing on this list.

### Also outstanding

- **A public demo with at least 30 real users**, and the traffic graph committed. Tracked in
  `PROGRESS.md`.
- **The hold-timeout decision.** [ADR 0012](adr/0012-hold-ttl.md) stays `proposed` until the demo
  produces a hold-to-checkout distribution. Synthetic load cannot produce one: a load script
  checks out in whatever time it is told to, and does not hesitate, re-read the price, or go and
  find a wallet.
