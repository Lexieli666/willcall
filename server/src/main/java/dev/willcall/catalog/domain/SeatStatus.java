package dev.willcall.catalog.domain;

/**
 * The seat lifecycle. {@code RELEASED} is deliberately absent: a released seat is indistinguishable
 * from an available one to a buyer, so release is an outcome recorded on the hold rather than a
 * state of the seat. Keeping it out of this enum means there is no state a client has to learn
 * about that carries no information.
 */
public enum SeatStatus {
  /** Nobody holds it and nobody has bought it. */
  AVAILABLE,
  /** Exactly one unexpired hold points at it. */
  HELD,
  /** Paid for, and present on exactly one confirmed order line. */
  SOLD,
  /** Withheld by the organizer: obstructed view, kill, accessibility pairing. Never sellable. */
  BLOCKED;

  public boolean isAcquirable() {
    return this == AVAILABLE;
  }
}
