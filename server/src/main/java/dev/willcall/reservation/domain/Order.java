package dev.willcall.reservation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Order(
    UUID id,
    UUID eventId,
    UUID holdGroupId,
    String userRef,
    OrderStatus status,
    int totalCents,
    String currency,
    String paymentReference,
    String failureCode,
    Instant createdAt,
    Instant confirmedAt,
    List<OrderLine> lines) {}
