package dev.willcall.catalog.domain;

import java.util.UUID;

public record PriceTier(UUID id, UUID eventId, String name, int amountCents, String currency) {}
