package dev.willcall.reservation.domain;

import java.time.Instant;
import java.util.UUID;

/** One held seat. A multi-seat request produces one {@link HoldGroup} and several of these. */
public record Hold(
    UUID id,
    UUID holdGroupId,
    UUID eventId,
    UUID seatId,
    String userRef,
    HoldStatus status,
    Instant expiresAt,
    Instant createdAt,
    Instant resolvedAt) {}
