package dev.willcall.reservation.domain;

import java.util.UUID;

public record OrderLine(UUID id, UUID orderId, UUID seatId, int priceCents) {}
