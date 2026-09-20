package dev.willcall.reservation.service;

import dev.willcall.reservation.store.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticks {@link OutboxRelay}.
 *
 * <p>Separate bean for the same reason the sweeper's scheduler is separate: a
 * {@code @Transactional} method called from another method of the same bean does not go through the
 * proxy and silently loses its transaction.
 */
@Component
public class OutboxScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);
  private static final int MAX_EVENTS_PER_TICK = 64;
  private static final int MAX_ROUNDS_PER_EVENT = 10;

  private final OutboxRelay relay;
  private final OutboxRepository outbox;
  private final Clock clock;
  private final boolean enabled;
  private final Duration retain;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public OutboxScheduler(
      OutboxRelay relay,
      OutboxRepository outbox,
      Clock clock,
      @Value("${willcall.outbox.enabled:true}") boolean enabled,
      @Value("${willcall.outbox.retain:PT1H}") Duration retain) {
    this.relay = relay;
    this.outbox = outbox;
    this.clock = clock;
    this.enabled = enabled;
    this.retain = retain;
  }

  @Scheduled(fixedDelayString = "${willcall.outbox.interval-ms:100}")
  public void tick() {
    if (!enabled) return;
    if (!running.compareAndSet(false, true)) return;
    try {
      drain();
    } catch (RuntimeException e) {
      log.warn("outbox relay pass failed; retrying on the next tick", e);
    } finally {
      running.set(false);
    }
  }

  /** Publishes everything currently pending. Returns how many entries went out. */
  public int drain() {
    int total = 0;
    List<UUID> eventIds = relay.eventsWithWork(MAX_EVENTS_PER_TICK);
    for (UUID eventId : eventIds) {
      int rounds = 0;
      int publishedForEvent;
      do {
        publishedForEvent = relay.publishBatchFor(eventId);
        total += publishedForEvent;
        rounds++;
      } while (publishedForEvent == relay.batchSize() && rounds < MAX_ROUNDS_PER_EVENT);
    }
    return total;
  }

  /**
   * Trims published entries. They are kept for an hour so a replica that reconnects can still
   * replay recent history; beyond that a client resyncs from a snapshot anyway, and the table would
   * otherwise grow by one row per seat change forever.
   */
  @Scheduled(fixedDelayString = "${willcall.outbox.trim-interval-ms:300000}")
  public void trim() {
    if (!enabled) return;
    int deleted = outbox.deletePublishedBefore(clock.instant().minus(retain));
    if (deleted > 0) log.info("trimmed {} published outbox entries", deleted);
  }
}
