package dev.willcall.catalog.service;

import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.domain.EventStatus;
import dev.willcall.catalog.domain.PriceTier;
import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.domain.SeatRow;
import dev.willcall.catalog.domain.SeatStatus;
import dev.willcall.catalog.domain.Section;
import dev.willcall.catalog.store.EventRepository;
import dev.willcall.catalog.store.SeatRepository;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and reads events and their seat maps.
 *
 * <p>Seat generation is bulk SQL, one statement per row rather than one per seat: a 5,000-seat
 * venue inserted a row at a time is 5,000 round trips, and the seeded dataset in Phase 4 is three
 * orders of magnitude bigger than that.
 */
@Service
public class CatalogService {

  private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

  private final EventRepository events;
  private final SeatRepository seats;

  public CatalogService(EventRepository events, SeatRepository seats) {
    this.events = events;
    this.seats = seats;
  }

  /** How a caller describes the venue layout when creating an event. */
  public record SectionSpec(String name, int rowCount, int seatsPerRow, String priceTierName) {}

  public record PriceTierSpec(String name, int amountCents, String currency) {}

  public record CreateEventSpec(
      String venueName,
      String eventName,
      Instant startsAt,
      Instant salesOpenAt,
      int holdTtlSeconds,
      int maxSeatsPerOrder,
      EventStatus status,
      List<PriceTierSpec> priceTiers,
      List<SectionSpec> sections,
      boolean waitingRoomEnabled,
      Double admissionRatePerSecond) {

    /** Most events need no queue; this keeps every existing caller saying so by omission. */
    public CreateEventSpec(
        String venueName,
        String eventName,
        Instant startsAt,
        Instant salesOpenAt,
        int holdTtlSeconds,
        int maxSeatsPerOrder,
        EventStatus status,
        List<PriceTierSpec> priceTiers,
        List<SectionSpec> sections) {
      this(
          venueName,
          eventName,
          startsAt,
          salesOpenAt,
          holdTtlSeconds,
          maxSeatsPerOrder,
          status,
          priceTiers,
          sections,
          false,
          null);
    }
  }

  public record EventDetail(
      Event event,
      List<PriceTier> priceTiers,
      List<Section> sections,
      List<SeatRow> rows,
      Map<SeatStatus, Integer> seatCounts) {}

  @Transactional
  public Event createEvent(CreateEventSpec spec) {
    if (spec.sections().isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "An event needs at least one section");
    }
    if (spec.priceTiers().isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "An event needs at least one price tier");
    }

    UUID venueId = events.insertVenue(spec.venueName(), "UTC");

    int capacity = spec.sections().stream().mapToInt(s -> s.rowCount() * s.seatsPerRow()).sum();

    UUID eventId =
        events.insertEvent(
            venueId,
            spec.eventName(),
            spec.startsAt(),
            spec.salesOpenAt(),
            capacity,
            spec.holdTtlSeconds(),
            spec.maxSeatsPerOrder(),
            spec.status());

    Map<String, UUID> tierIds = new java.util.HashMap<>();
    for (PriceTierSpec tier : spec.priceTiers()) {
      tierIds.put(
          tier.name(),
          events.insertPriceTier(eventId, tier.name(), tier.amountCents(), tier.currency()));
    }

    int sectionOrder = 0;
    int globalRowOrder = 0;
    for (SectionSpec section : spec.sections()) {
      UUID sectionId = events.insertSection(eventId, section.name(), sectionOrder++);
      UUID tierId = tierIds.get(section.priceTierName());
      if (tierId == null) {
        throw new ApiException(
            ErrorCode.INVALID_REQUEST,
            "Section " + section.name() + " names a price tier that does not exist");
      }
      for (int r = 0; r < section.rowCount(); r++) {
        String rowLabel = rowLabel(r);
        UUID rowId =
            events.insertRow(sectionId, eventId, rowLabel, globalRowOrder++, section.seatsPerRow());
        seats.insertSeats(
            eventId, rowId, tierId, section.name() + "-" + rowLabel, section.seatsPerRow());
      }
    }

    if (spec.waitingRoomEnabled() || spec.admissionRatePerSecond() != null) {
      events.updateWaitingRoom(eventId, spec.waitingRoomEnabled(), spec.admissionRatePerSecond());
    }

    log.info(
        "created event {} with capacity {}, waiting room {}",
        eventId,
        capacity,
        spec.waitingRoomEnabled() ? "on" : "off");
    return events
        .find(eventId)
        .orElseThrow(() -> new IllegalStateException("event vanished after insert"));
  }

  /** A, B, ... Z, AA, AB, ... so a 700-row venue still has readable labels. */
  static String rowLabel(int index) {
    StringBuilder out = new StringBuilder();
    int n = index;
    do {
      out.insert(0, (char) ('A' + (n % 26)));
      n = n / 26 - 1;
    } while (n >= 0);
    return out.toString();
  }

  @Transactional(readOnly = true)
  public EventDetail detail(UUID eventId) {
    Event event =
        events.find(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
    return new EventDetail(
        event,
        events.findPriceTiers(eventId),
        events.findSections(eventId),
        events.findRows(eventId),
        seats.countByStatus(eventId));
  }

  @Transactional(readOnly = true)
  public List<Seat> seatMap(UUID eventId) {
    if (events.find(eventId).isEmpty()) throw new ApiException(ErrorCode.EVENT_NOT_FOUND);
    return seats.findByEvent(eventId);
  }

  @Transactional(readOnly = true)
  public List<Event> onSale() {
    return new ArrayList<>(events.findAllOnSale());
  }

  /**
   * Turns the waiting room on or off and records the measured admission rate.
   *
   * <p>The rate is a measurement, not a preference: it is what this event's reservation path was
   * observed to survive. Setting it from a guess is how a queue ends up admitting faster than the
   * thing behind it can serve.
   */
  @Transactional
  public void setWaitingRoom(UUID eventId, boolean enabled, Double ratePerSecond) {
    if (events.find(eventId).isEmpty()) throw new ApiException(ErrorCode.EVENT_NOT_FOUND);
    events.updateWaitingRoom(eventId, enabled, ratePerSecond);
  }

  @Transactional
  public void setStatus(UUID eventId, EventStatus status) {
    if (events.find(eventId).isEmpty()) throw new ApiException(ErrorCode.EVENT_NOT_FOUND);
    events.updateStatus(eventId, status);
  }
}
