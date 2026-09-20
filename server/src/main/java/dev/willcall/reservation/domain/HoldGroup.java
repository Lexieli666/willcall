package dev.willcall.reservation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldGroup(
    UUID id,
    UUID eventId,
    String userRef,
    HoldStatus status,
    Instant expiresAt,
    int seatCount,
    Instant createdAt,
    Instant resolvedAt,
    List<UUID> seatIds) {

  public boolean isExpiredAt(Instant now) {
    return !expiresAt.isAfter(now);
  }
}
