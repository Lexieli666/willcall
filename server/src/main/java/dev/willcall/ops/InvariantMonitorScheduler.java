package dev.willcall.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ticks {@link InvariantMonitor}.
 *
 * <p>A separate bean for the reason this codebase has already paid for twice: a
 * {@code @Transactional} method invoked from another method of the same bean does not go through
 * the proxy and silently runs with no transaction. Here that would mean an advisory lock taken on
 * one pooled connection and never released.
 */
@Component
public class InvariantMonitorScheduler {

  private static final Logger log = LoggerFactory.getLogger(InvariantMonitorScheduler.class);

  private final InvariantMonitor monitor;
  private final TransactionTemplate transactions;
  private final boolean enabled;

  public InvariantMonitorScheduler(
      InvariantMonitor monitor,
      TransactionTemplate transactions,
      @Value("${willcall.invariants.monitor-enabled:true}") boolean enabled) {
    this.monitor = monitor;
    this.transactions = transactions;
    this.enabled = enabled;
  }

  @Scheduled(
      fixedDelayString = "${willcall.invariants.interval-ms:60000}",
      initialDelayString = "${willcall.invariants.initial-delay-ms:15000}")
  public void tick() {
    if (!enabled) return;
    try {
      transactions.execute(status -> monitor.checkIfLockAcquired());
    } catch (RuntimeException e) {
      // Deliberately not resetting the gauge: a database that cannot be reached is not a database
      // with no violations, and the age gauge is what makes the difference visible.
      log.warn("invariant check could not run; the age gauge will rise", e);
    }
  }
}
