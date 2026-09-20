# ADR 0009: Render the seat map as real DOM, not canvas

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

A 5,000-seat map is 5,000 interactive elements. The usual advice for that many is a canvas: one
element, one paint, no layout cost per seat, and it scales to numbers where the DOM does not.

The project's accessibility requirements make that advice wrong here. A canvas is a single
unlabelled element to a screen reader and nothing at all to a keyboard. Making it accessible means
building a parallel DOM structure that mirrors the canvas — at which point the DOM cost is being
paid anyway, plus the cost of keeping two representations in step.

## Decision

Every seat is a real `<button>`. The map uses ARIA grid semantics with a roving tabindex, and the
render cost is treated as a budget to hold rather than as a reason to give up the semantics.

A **text-only list mode** is offered alongside, not as a fallback. For someone looking for "two
seats together under $60", a filtered list is a better interface than arrowing around a grid
counting gaps. For someone who cares where in the venue they sit, the grid is better. Both are
first-class and the toggle is a preference, not an accessibility mode.

## Alternatives considered

- **Canvas with a parallel DOM layer for assistive technology.** Rejected: two representations
  that must agree, where a divergence is invisible to a sighted developer and total to a screen
  reader user.
- **Virtualise the grid, rendering only visible rows.** Rejected as the primary mechanism. It
  removes rows from the accessibility tree and from keyboard reach, so "press End to jump to the
  last row" stops working. `content-visibility: auto` gets most of the same paint saving while
  keeping every row present and reachable, which is why it is used instead.
- **Fewer, larger seats — a simplified map.** Rejected: the seat you get is the product.

## Consequences

- The render budget is real and had to be earned. Three things bought it: per-row memoisation on
  a revision counter with in-place array mutation, `content-visibility: auto`, and caching
  `Intl.NumberFormat` instances. The last was the largest single cost — building one formatter per
  seat while composing accessible names took the measured render from 42 ms to 126 ms, over
  budget, and nothing about the code looked expensive.
- The measured figure is published with the page and asserted in CI, so a regression is a failed
  build rather than a slow page nobody profiles.
- Above roughly 20,000 seats this decision would need revisiting. That is stated rather than
  discovered: the project's target is 5,000.

## Falsifier

`seatmap-performance.spec.ts` builds a 5,000-seat event, reads the `willcall:seatmap:render`
measure the application publishes, prints it, and fails above 120 ms. A second test asserts that a
delta repaints only the rows it touches, by checking the other seats keep their state — a full
rebuild would be correct and would still lose scroll position and focus, which is the real
failure. `purchase.spec.ts` drives a complete purchase through the grid and through the list mode,
and scans both for axe violations.
