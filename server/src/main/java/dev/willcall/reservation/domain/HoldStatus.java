package dev.willcall.reservation.domain;

/**
 * A hold's lifecycle. Exactly one transition out of {@code ACTIVE} ever succeeds, and which one
 * wins is decided by whoever gets the row lock first — that is what makes confirm-versus-expire and
 * cancel-versus-confirm resolve consistently instead of both appearing to succeed.
 */
public enum HoldStatus {
  ACTIVE,
  CONFIRMED,
  CANCELLED,
  EXPIRED;

  public boolean isTerminal() {
    return this != ACTIVE;
  }
}
