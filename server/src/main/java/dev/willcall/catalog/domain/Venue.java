package dev.willcall.catalog.domain;

import java.util.UUID;

public record Venue(UUID id, String name, String timezone) {}
