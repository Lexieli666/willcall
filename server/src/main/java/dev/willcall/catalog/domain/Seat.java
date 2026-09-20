package dev.willcall.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A seat as the database holds it. {@code version} increments on every status change and is carried
 * in real-time deltas so a client can discard a delta it has already applied.
 */
public record Seat(
    UUID id,
    UUID eventId,
    UUID rowId,
    UUID priceTierId,
    int seatNumber,
    String label,
    SeatStatus status,
    long version,
    Instant updatedAt) {}
