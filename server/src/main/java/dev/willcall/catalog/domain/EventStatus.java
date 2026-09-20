package dev.willcall.catalog.domain;

public enum EventStatus {
  DRAFT,
  ON_SALE,
  PAUSED,
  CLOSED;

  public boolean acceptsHolds() {
    return this == ON_SALE;
  }
}
