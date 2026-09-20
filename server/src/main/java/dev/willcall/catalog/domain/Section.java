package dev.willcall.catalog.domain;

import java.util.UUID;

public record Section(UUID id, UUID eventId, String name, int displayOrder) {}
