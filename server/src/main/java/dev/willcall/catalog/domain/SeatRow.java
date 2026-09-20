package dev.willcall.catalog.domain;

import java.util.UUID;

public record SeatRow(
    UUID id, UUID sectionId, UUID eventId, String label, int displayOrder, int seatCount) {}
