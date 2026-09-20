package dev.willcall.waitingroom;

import dev.willcall.catalog.domain.Event;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs an admission pass on every replica.
 *
 * <p>No leader election, because none is needed: the bucket lives in Redis and the refill and the
 * pop happen inside one Lua script, so N replicas calling this concurrently admit at the configured
 * rate in total rather than N times it. A leader would add a dependency and a failure mode where it
 * is dead but not yet noticed.
 */
@Component
public class AdmissionScheduler {

  private static final Logger log = LoggerFactory.getLogger(AdmissionScheduler.class);

  private final WaitingRoomService waitingRoom;
  private final boolean enabled;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Timer passTimer;

  /** Notified about each admitted buyer, so the stream can tell them without them polling. */
  private volatile BiConsumer<Event, List<String>> listener = (event, users) -> {};

  public AdmissionScheduler(
      WaitingRoomService waitingRoom,
      MeterRegistry meterRegistry,
      @Value("${willcall.waitingroom.admitter-enabled:true}") boolean enabled) {
    this.waitingRoom = waitingRoom;
    this.enabled = enabled;
    this.passTimer =
        Timer.builder("willcall.queue.admission_pass")
            .publishPercentiles(0.5, 0.99)
            .register(meterRegistry);
  }

  public void onAdmitted(BiConsumer<Event, List<String>> consumer) {
    this.listener = consumer;
  }

  @Scheduled(fixedDelayString = "${willcall.waitingroom.admitter-interval-ms:250}")
  public void tick() {
    if (!enabled) return;
    if (!running.compareAndSet(false, true)) return;
    try {
      passTimer.record(this::admitAll);
    } catch (RuntimeException e) {
      log.warn("admission pass failed; retrying on the next tick", e);
    } finally {
      running.set(false);
    }
  }

  /** One pass over every event with a queue. Exposed so a test can say "now". */
  public int admitAll() {
    int total = 0;
    for (Event event : waitingRoom.eventsWithQueues()) {
      List<String> users = waitingRoom.admitBatch(event);
      if (users.isEmpty()) continue;
      total += users.size();
      try {
        listener.accept(event, users);
      } catch (RuntimeException e) {
        // Telling somebody they were admitted is best effort; being admitted is not. A client
        // that misses the push finds out on its next poll.
        log.warn("could not notify {} admitted buyers for event {}", users.size(), event.id(), e);
      }
    }
    return total;
  }
}
