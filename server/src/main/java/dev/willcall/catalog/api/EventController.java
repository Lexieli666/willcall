package dev.willcall.catalog.api;

import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.domain.EventStatus;
import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.service.CatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

  private final CatalogService catalog;

  public EventController(CatalogService catalog) {
    this.catalog = catalog;
  }

  public record PriceTierRequest(
      @NotBlank String name, @Min(0) int amountCents, @Size(min = 3, max = 3) String currency) {}

  public record SectionRequest(
      @NotBlank String name,
      @Min(1) @Max(1000) int rowCount,
      @Min(1) @Max(1000) int seatsPerRow,
      @NotBlank String priceTierName) {}

  public record CreateEventRequest(
      @NotBlank String venueName,
      @NotBlank String eventName,
      Instant startsAt,
      Instant salesOpenAt,
      @Min(15) @Max(3600) Integer holdTtlSeconds,
      @Min(1) @Max(50) Integer maxSeatsPerOrder,
      String status,
      @NotEmpty List<@Valid PriceTierRequest> priceTiers,
      @NotEmpty List<@Valid SectionRequest> sections,
      Boolean waitingRoomEnabled,
      Double admissionRatePerSecond) {}

  public record EventSummary(
      UUID id,
      String name,
      Instant startsAt,
      Instant salesOpenAt,
      int capacity,
      int holdTtlSeconds,
      int maxSeatsPerOrder,
      String status,
      long lastSequence,
      boolean waitingRoomEnabled,
      Double admissionRatePerSecond) {

    static EventSummary of(Event event) {
      return new EventSummary(
          event.id(),
          event.name(),
          event.startsAt(),
          event.salesOpenAt(),
          event.capacity(),
          event.holdTtlSeconds(),
          event.maxSeatsPerOrder(),
          event.status().name(),
          event.lastSequence(),
          event.waitingRoomEnabled(),
          event.admissionRatePerSecond());
    }
  }

  public record SeatView(
      UUID id, UUID rowId, int number, String label, String status, long version, int priceCents) {}

  public record SeatMapResponse(
      UUID eventId,
      long sequence,
      List<SectionView> sections,
      int availableCount,
      int heldCount,
      int soldCount) {}

  public record SectionView(UUID id, String name, int displayOrder, List<RowView> rows) {}

  public record RowView(UUID id, String label, int displayOrder, List<SeatView> seats) {}

  @PostMapping
  public ResponseEntity<EventSummary> create(@Valid @RequestBody CreateEventRequest request) {
    Instant now = Instant.now();
    CatalogService.CreateEventSpec spec =
        new CatalogService.CreateEventSpec(
            request.venueName(),
            request.eventName(),
            request.startsAt() == null ? now.plusSeconds(86_400) : request.startsAt(),
            request.salesOpenAt() == null ? now : request.salesOpenAt(),
            request.holdTtlSeconds() == null ? 120 : request.holdTtlSeconds(),
            request.maxSeatsPerOrder() == null ? 8 : request.maxSeatsPerOrder(),
            request.status() == null ? EventStatus.ON_SALE : EventStatus.valueOf(request.status()),
            request.priceTiers().stream()
                .map(
                    t ->
                        new CatalogService.PriceTierSpec(
                            t.name(), t.amountCents(), t.currency() == null ? "USD" : t.currency()))
                .toList(),
            request.sections().stream()
                .map(
                    s ->
                        new CatalogService.SectionSpec(
                            s.name(), s.rowCount(), s.seatsPerRow(), s.priceTierName()))
                .toList(),
            Boolean.TRUE.equals(request.waitingRoomEnabled()),
            request.admissionRatePerSecond());

    Event event = catalog.createEvent(spec);
    return ResponseEntity.status(201).body(EventSummary.of(event));
  }

  @GetMapping
  public List<EventSummary> list() {
    return catalog.onSale().stream().map(EventSummary::of).toList();
  }

  @GetMapping("/{eventId}")
  public Map<String, Object> detail(@PathVariable UUID eventId) {
    CatalogService.EventDetail detail = catalog.detail(eventId);
    return Map.of(
        "event", EventSummary.of(detail.event()),
        "priceTiers", detail.priceTiers(),
        "sections", detail.sections(),
        "rows", detail.rows(),
        "seatCounts", detail.seatCounts());
  }

  /**
   * The full seat map.
   *
   * <p>Returned as one nested document rather than a flat list so the client renders sections and
   * rows without a second grouping pass; for a 5,000-seat venue that pass is measurable against the
   * 120 ms render budget.
   */
  @GetMapping("/{eventId}/seats")
  public SeatMapResponse seatMap(@PathVariable UUID eventId) {
    return SeatMapAssembler.assemble(catalog.detail(eventId), catalog.seatMap(eventId));
  }

  /**
   * Turns the waiting room on or off for an event.
   *
   * <p>The rate is the measured admissions per second this event's reservation path survives.
   * Omitting it leaves the service-wide default in force, which is a starting point rather than a
   * measurement, and `docs/capacity-model.md` says so.
   */
  @PostMapping("/{eventId}/waiting-room")
  public ResponseEntity<Void> setWaitingRoom(
      @PathVariable UUID eventId,
      @RequestParam(defaultValue = "true") boolean enabled,
      @RequestParam(required = false) Double ratePerSecond) {
    catalog.setWaitingRoom(eventId, enabled, ratePerSecond);
    return ResponseEntity.noContent().build();
  }

  /** Test and demo helper: move an event between DRAFT, ON_SALE, PAUSED and CLOSED. */
  @PostMapping("/{eventId}/status/{status}")
  public ResponseEntity<Void> setStatus(@PathVariable UUID eventId, @PathVariable String status) {
    catalog.setStatus(eventId, EventStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT)));
    return ResponseEntity.noContent().build();
  }

  /** Kept out of the controller so the shape of the response has one owner. */
  static final class SeatMapAssembler {
    private SeatMapAssembler() {}

    static SeatMapResponse assemble(CatalogService.EventDetail detail, List<Seat> seats) {
      Map<UUID, Integer> priceByTier =
          detail.priceTiers().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      dev.willcall.catalog.domain.PriceTier::id,
                      dev.willcall.catalog.domain.PriceTier::amountCents));

      Map<UUID, List<Seat>> seatsByRow =
          seats.stream().collect(java.util.stream.Collectors.groupingBy(Seat::rowId));

      Map<UUID, List<dev.willcall.catalog.domain.SeatRow>> rowsBySection =
          detail.rows().stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      dev.willcall.catalog.domain.SeatRow::sectionId));

      List<SectionView> sections =
          detail.sections().stream()
              .map(
                  section ->
                      new SectionView(
                          section.id(),
                          section.name(),
                          section.displayOrder(),
                          rowsBySection.getOrDefault(section.id(), List.of()).stream()
                              .sorted(
                                  java.util.Comparator.comparingInt(
                                      dev.willcall.catalog.domain.SeatRow::displayOrder))
                              .map(
                                  row ->
                                      new RowView(
                                          row.id(),
                                          row.label(),
                                          row.displayOrder(),
                                          seatsByRow.getOrDefault(row.id(), List.of()).stream()
                                              .sorted(
                                                  java.util.Comparator.comparingInt(
                                                      Seat::seatNumber))
                                              .map(
                                                  seat ->
                                                      new SeatView(
                                                          seat.id(),
                                                          seat.rowId(),
                                                          seat.seatNumber(),
                                                          seat.label(),
                                                          seat.status().name(),
                                                          seat.version(),
                                                          priceByTier.getOrDefault(
                                                              seat.priceTierId(), 0)))
                                              .toList()))
                              .toList()))
              .toList();

      return new SeatMapResponse(
          detail.event().id(),
          detail.event().lastSequence(),
          sections,
          detail.seatCounts().getOrDefault(dev.willcall.catalog.domain.SeatStatus.AVAILABLE, 0),
          detail.seatCounts().getOrDefault(dev.willcall.catalog.domain.SeatStatus.HELD, 0),
          detail.seatCounts().getOrDefault(dev.willcall.catalog.domain.SeatStatus.SOLD, 0));
    }
  }
}
