package dev.willcall.waitingroom;

import java.time.Instant;

/**
 * Where a buyer stands.
 *
 * @param state what to show them
 * @param position 1-based place in the queue, or 0 once admitted
 * @param queueLength how many are waiting, so "position 400" has a denominator
 * @param estimatedWaitSeconds derived from the measured admission rate, null when it is unknown
 * @param beyondInventory true when there are more people ahead than seats left. Telling somebody
 *     they are queueing for nothing is kinder than letting them find out at the front, and it is
 *     the part most queue implementations leave out.
 * @param joinedAt the arrival time that fixed their place
 * @param admissionToken present only once admitted
 */
public record QueuePosition(
    State state,
    long position,
    long queueLength,
    Long estimatedWaitSeconds,
    boolean beyondInventory,
    Instant joinedAt,
    String admissionToken) {

  public enum State {
    /** Waiting, with a place. */
    WAITING,
    /** Through the queue; the token is attached. */
    ADMITTED,
    /** Not in the queue at all. */
    NOT_QUEUED,
    /**
     * Redis is unavailable, so the queue cannot be consulted. Admission is open while this lasts:
     * refusing to sell because the *queue* is down is worse than selling unpaced for a few minutes.
     */
    DEGRADED_OPEN
  }
}
