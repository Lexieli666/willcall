package dev.willcall.reservation.service;

import dev.willcall.reservation.store.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox, assigning each entry the next sequence number for its event.
 *
 * <h2>Why the sequence is assigned here and not by the writer</h2>
 *
 * A gap-free per-event counter has to be written under a lock. Doing that in the hold transaction
 * would put every hold for one event behind a single row — the exact serialisation a flash sale
 * cannot afford. Here it costs nothing: the relay is already batching, and one lock per batch is
 * amortised across hundreds of deltas.
 *
 * <h2>Why an advisory lock rather than SKIP LOCKED</h2>
 *
 * Every replica runs a relay. {@code SKIP LOCKED} would let two of them publish entries for the
 * same event concurrently and assign sequence numbers out of order; every connected client would
 * then see a gap and resync, turning a small optimisation into a stampede. A per-event
 * transaction-scoped advisory lock gives one publisher per event at a time while different events
 * still publish in parallel.
 *
 * <p>The publish callback is invoked inside the transaction. That is deliberate: a delta must not
 * reach a browser before the row that says it was published commits, or a replica restart would
 * replay it with a different sequence number.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outbox;
  private final int batchSize;
  private final Counter published;
  private volatile Consumer<PublishedEntry> subscriber = entry -> {};

  public OutboxRelay(
      OutboxRepository outbox,
      MeterRegistry meterRegistry,
      @Value("${willcall.outbox.batch-size:500}") int batchSize) {
    this.outbox = outbox;
    this.batchSize = batchSize;
    this.published =
        Counter.builder("willcall.outbox.published")
            .description("Outbox entries assigned a sequence number and handed to the fan-out")
            .register(meterRegistry);
  }

  /** An outbox entry with the sequence number it was given. */
  public record PublishedEntry(
      UUID eventId, long sequenceNo, String type, UUID aggregateId, String payload) {}

  /**
   * Registers the fan-out. Set by the real-time module at start-up; until then the relay still
   * drains the table, so the outbox cannot grow without bound just because nobody is listening.
   */
  public void subscribe(Consumer<PublishedEntry> consumer) {
    this.subscriber = consumer;
  }

  public int batchSize() {
    return batchSize;
  }

  public List<UUID> eventsWithWork(int limit) {
    return outbox.eventsWithPendingEntries(limit);
  }

  /**
   * Publishes one batch for one event.
   *
   * @return the number of entries published, or 0 if another replica holds the event's lock
   */
  @Transactional
  public int publishBatchFor(UUID eventId) {
    if (!outbox.tryLockEvent(eventId)) return 0;

    List<OutboxRepository.Entry> pending = outbox.pendingForEvent(eventId, batchSize);
    if (pending.isEmpty()) return 0;

    long firstSequence = outbox.reserveSequenceRange(eventId, pending.size());

    long sequence = firstSequence;
    for (OutboxRepository.Entry entry : pending) {
      outbox.markPublished(entry.id(), sequence);
      try {
        subscriber.accept(
            new PublishedEntry(
                eventId, sequence, entry.type(), entry.aggregateId(), entry.payload()));
      } catch (RuntimeException e) {
        // Fan-out is best effort by design: a client that misses a delta detects the gap and
        // resyncs. Failing the transaction here would stall the sequence for everyone.
        log.warn("fan-out rejected entry {} for event {}", entry.id(), eventId, e);
      }
      sequence++;
    }

    published.increment(pending.size());
    return pending.size();
  }
}
