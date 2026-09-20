package dev.willcall.realtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The payload of the {@code snapshot} frame that opens every stream. */
public record SeatSnapshotMessage(
    UUID eventId,
    long sequence,
    Instant serverTime,
    long coalesceWindowMs,
    List<SeatDeltaMessage.SeatChange> seats,
    SeatDeltaMessage.SeatCounts counts) {}
