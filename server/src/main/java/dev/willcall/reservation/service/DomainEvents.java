package dev.willcall.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.willcall.catalog.domain.SeatStatus;
import dev.willcall.reservation.store.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes seat changes to the outbox inside the caller's transaction.
 *
 * <p>{@code MANDATORY} propagation is a guard, not a formality: a delta written outside the
 * transaction that changed the seat could announce a state that then rolled back. The annotation
 * turns that mistake into a start-up-time-visible failure rather than a rare inconsistency.
 */
@Component
public class DomainEvents {

  public static final String TYPE_SEAT_CHANGED = "seat.changed";

  private final OutboxRepository outbox;
  private final ObjectMapper objectMapper;

  public DomainEvents(OutboxRepository outbox, ObjectMapper objectMapper) {
    this.outbox = outbox;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void seatsChanged(UUID eventId, List<UUID> seatIds, SeatStatus status, long[] versions) {
    if (seatIds.isEmpty()) return;
    List<OutboxRepository.Entry> entries =
        java.util.stream.IntStream.range(0, seatIds.size())
            .mapToObj(
                i ->
                    new OutboxRepository.Entry(
                        0L,
                        eventId,
                        "seat",
                        seatIds.get(i),
                        TYPE_SEAT_CHANGED,
                        toJson(
                            new SeatDelta(
                                seatIds.get(i), status, versions.length > i ? versions[i] : 0L)),
                        null,
                        // The database stamps created_at on insert; nothing useful can be said
                        // here, because the transaction has not committed yet.
                        null))
            .toList();
    outbox.appendAll(entries);
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("outbox payload is not serialisable", e);
    }
  }
}
