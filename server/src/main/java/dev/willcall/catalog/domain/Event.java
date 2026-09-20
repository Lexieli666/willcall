package dev.willcall.catalog.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Event(
    UUID id,
    UUID venueId,
    String name,
    Instant startsAt,
    Instant salesOpenAt,
    int capacity,
    int holdTtlSeconds,
    int maxSeatsPerOrder,
    EventStatus status,
    long lastSequence,
    boolean waitingRoomEnabled,
    Double admissionRatePerSecond) {

  public Duration holdTtl() {
    return Duration.ofSeconds(holdTtlSeconds);
  }
}
