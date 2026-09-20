package dev.willcall.reservation.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link HoldExpirySweeper}.
 *
 * <p>Separate from the sweeper for one specific reason: Spring's {@code @Transactional} works
 * through a proxy, so a transactional method invoked from another method of the <em>same</em> bean
 * runs with no transaction at all. A scheduler loop calling {@code sweepOnce()} on itself would
 * have silently expired holds outside a transaction, which is exactly the kind of bug that only
 * shows up as a partial release under load.
 */
@Component
public class HoldExpiryScheduler {

  private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

  private static final int MAX_ROUNDS_PER_TICK = 20;

  private final HoldExpirySweeper sweeper;
  private final boolean enabled;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Timer sweepTimer;

  public HoldExpiryScheduler(
      HoldExpirySweeper sweeper,
      MeterRegistry meterRegistry,
      @Value("${willcall.sweeper.enabled:true}") boolean enabled) {
    this.sweeper = sweeper;
    this.enabled = enabled;
    this.sweepTimer =
        Timer.builder("willcall.sweeper.duration")
            .description("Wall time of one sweeper tick")
            .publishPercentiles(0.5, 0.99)
            .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${willcall.sweeper.interval-ms:250}")
  public void tick() {
    if (!enabled) return;
    // One pass at a time per replica: a slow sweep under load must not queue behind itself and
    // turn a latency problem into a lock-contention problem.
    if (!running.compareAndSet(false, true)) return;
    try {
      sweepTimer.record(this::drain);
    } catch (RuntimeException e) {
      log.warn("sweeper pass failed; retrying on the next tick", e);
    } finally {
      running.set(false);
    }
  }

  /**
   * Keeps sweeping while batches come back full, bounded so one tick cannot monopolise a connection
   * during a mass expiry.
   *
   * @return holds expired in this pass
   */
  public int drain() {
    int total = 0;
    int rounds = 0;
    int claimed;
    do {
      claimed = sweeper.sweepOnce();
      total += claimed;
      rounds++;
    } while (claimed == sweeper.batchSize() && rounds < MAX_ROUNDS_PER_TICK);
    return total;
  }
}
