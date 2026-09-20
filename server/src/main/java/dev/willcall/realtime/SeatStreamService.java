package dev.willcall.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.willcall.allocation.ContiguousSeatIndex;
import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.domain.SeatStatus;
import dev.willcall.catalog.store.EventRepository;
import dev.willcall.catalog.store.SeatRepository;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import dev.willcall.reservation.service.OutboxRelay;
import dev.willcall.reservation.store.OutboxRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Turns a connect request into a live stream: snapshot or catch-up, then deltas.
 *
 * <p>Also the bridge between the outbox relay and the fan-out. The relay hands every published
 * entry here; this class decodes it and passes it to the bus, which delivers locally and publishes
 * to the other replicas.
 */
@Service
public class SeatStreamService {

  private static final Logger log = LoggerFactory.getLogger(SeatStreamService.class);

  private final EventRepository events;
  private final SeatRepository seats;
  private final OutboxRepository outbox;
  private final OutboxRelay relay;
  private final SeatDeltaBus bus;
  private final EventStreamHub hub;
  private final ContiguousSeatIndex contiguousIndex;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public SeatStreamService(
      EventRepository events,
      SeatRepository seats,
      OutboxRepository outbox,
      OutboxRelay relay,
      SeatDeltaBus bus,
      EventStreamHub hub,
      ContiguousSeatIndex contiguousIndex,
      ObjectMapper objectMapper,
      Clock clock) {
    this.events = events;
    this.seats = seats;
    this.outbox = outbox;
    this.relay = relay;
    this.bus = bus;
    this.hub = hub;
    this.contiguousIndex = contiguousIndex;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @PostConstruct
  void subscribeToTheOutbox() {
    relay.subscribe(this::onPublished);
  }

  private void onPublished(OutboxRelay.PublishedEntry entry) {
    if (!"seat.changed".equals(entry.type())) return;
    try {
      SeatDeltaMessage.SeatChange change =
          objectMapper.readValue(entry.payload(), SeatChangePayload.class).toChange();
      // The allocation index reads the same stream as the browsers, so there is one source of
      // seat changes rather than two that can disagree.
      contiguousIndex.apply(entry.eventId(), change.id(), change.status());
      bus.publish(entry.eventId(), entry.sequenceNo(), change, null);
    } catch (Exception e) {
      log.warn("could not decode outbox entry {} for fan-out", entry.aggregateId(), e);
    }
  }

  /** The outbox payload shape written by {@code DomainEvents}. */
  private record SeatChangePayload(UUID seatId, SeatStatus status, long version) {
    SeatDeltaMessage.SeatChange toChange() {
      return new SeatDeltaMessage.SeatChange(seatId, status, version);
    }
  }

  /**
   * Opens a stream.
   *
   * <p>A reconnect carrying {@code Last-Event-ID} is served from the retained outbox history when
   * that history is complete and contiguous from the client's cursor. Otherwise the client gets a
   * fresh snapshot — which is also what "the sequence moved on further than we keep history" and
   * "this replica has never heard of you" both mean.
   */
  public SseEmitter open(UUID eventId, Long lastEventId) {
    Event event =
        events.find(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));

    String connectionId = UUID.randomUUID().toString();
    SseEmitter emitter = hub.open(eventId, connectionId);

    List<SeatDeltaMessage> catchUp =
        lastEventId == null ? List.of() : catchUpFrom(event, lastEventId);

    if (!catchUp.isEmpty()) {
      for (SeatDeltaMessage delta : catchUp) {
        hub.sendCatchUp(eventId, connectionId, delta);
      }
      log.debug(
          "stream {} resumed from sequence {} with {} deltas",
          connectionId,
          lastEventId,
          catchUp.size());
    } else if (lastEventId != null && lastEventId == event.lastSequence()) {
      // Nothing happened while the client was away. Send the snapshot anyway: it is one message
      // and it removes any doubt about what the client is holding.
      hub.sendSnapshot(eventId, connectionId, snapshot(event));
    } else {
      if (lastEventId != null) {
        hub.sendResync(eventId, connectionId, event.lastSequence(), "history_unavailable");
      }
      hub.sendSnapshot(eventId, connectionId, snapshot(event));
    }

    return emitter;
  }

  /**
   * Deltas from {@code lastEventId + 1} to the current sequence, or empty when the history is
   * incomplete.
   *
   * <p>Contiguity is checked rather than assumed. A hole means the trimmer has already removed part
   * of what the client needs, and sending the remainder would leave the client silently wrong —
   * worse than telling it to resync.
   */
  @Transactional(readOnly = true)
  public List<SeatDeltaMessage> catchUpFrom(Event event, long lastEventId) {
    if (lastEventId >= event.lastSequence()) return List.of();

    long expected = lastEventId + 1;
    List<OutboxRepository.Entry> history = outbox.publishedSince(event.id(), lastEventId, 2_000);
    if (history.isEmpty()) return List.of();

    List<SeatDeltaMessage> deltas = new ArrayList<>(history.size());
    for (OutboxRepository.Entry entry : history) {
      Long sequence = entry.sequenceNo();
      if (sequence == null || sequence != expected) return List.of();
      expected++;
      try {
        SeatDeltaMessage.SeatChange change =
            objectMapper.readValue(entry.payload(), SeatChangePayload.class).toChange();
        deltas.add(new SeatDeltaMessage(sequence, sequence, List.of(change), null));
      } catch (Exception e) {
        return List.of();
      }
    }

    // The history must reach all the way to the present, or the client would resume into a gap.
    return expected - 1 == event.lastSequence() ? deltas : List.of();
  }

  @Transactional(readOnly = true)
  public SeatSnapshotMessage snapshot(Event event) {
    List<Seat> all = seats.findByEvent(event.id());
    List<SeatDeltaMessage.SeatChange> changes = new ArrayList<>(all.size());
    for (Seat seat : all) {
      changes.add(new SeatDeltaMessage.SeatChange(seat.id(), seat.status(), seat.version()));
    }
    Map<SeatStatus, Integer> counts = seats.countByStatus(event.id());
    return new SeatSnapshotMessage(
        event.id(),
        event.lastSequence(),
        clock.instant(),
        hub.coalesceWindowMs(),
        changes,
        new SeatDeltaMessage.SeatCounts(
            counts.getOrDefault(SeatStatus.AVAILABLE, 0),
            counts.getOrDefault(SeatStatus.HELD, 0),
            counts.getOrDefault(SeatStatus.SOLD, 0)));
  }

  public SeatSnapshotMessage snapshotOf(UUID eventId) {
    return snapshot(
        events.find(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND)));
  }
}
