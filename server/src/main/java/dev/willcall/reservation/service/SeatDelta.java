package dev.willcall.reservation.service;

import dev.willcall.catalog.domain.SeatStatus;
import java.util.UUID;

/**
 * One seat's new state, as the real-time layer will publish it.
 *
 * <p>{@code version} rather than a timestamp: a client applies a delta only if its version is
 * higher than the one it already has for that seat, which makes the protocol tolerant of duplicate
 * and out-of-order delivery without needing exactly-once anything.
 */
public record SeatDelta(UUID seatId, SeatStatus status, long version) {}
