package dev.willcall.waitingroom;

import java.time.Instant;

/**
 * Proof that a buyer reached the front of the queue.
 *
 * @param eventId the event it is valid for; a token for one event must not open another
 * @param userRef the buyer it was issued to
 * @param issuedAt when the queue admitted them
 * @param expiresAt when it stops working, so an admission cannot be hoarded and used tomorrow
 */
public record AdmissionToken(String eventId, String userRef, Instant issuedAt, Instant expiresAt) {

  public boolean isExpiredAt(Instant now) {
    return !expiresAt.isAfter(now);
  }
}
