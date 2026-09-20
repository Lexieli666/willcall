package dev.willcall.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the invariant checks and publishes the result as a gauge.
 *
 * <p>{@code docs/slo.md} listed {@code WillcallOversellDetected} as a page-level alert, and the
 * alert could not fire: the invariant was checked by a script after a load run and by a POST
 * endpoint somebody had to call. An alert that depends on a person remembering to run something is
 * a checklist item wearing an alert's name. This is the metric the alert reads.
 *
 * <p>One replica per tick, through a <em>transaction-scoped</em> advisory lock. The capacity check
 * scans every event — 98 ms against 50,008 of them on the seeded dataset — and three replicas doing
 * that on their own schedules is three times the cost for the same answer.
 *
 * <p>{@code pg_try_advisory_xact_lock} and not {@code pg_try_advisory_lock}. A session-scoped lock
 * would be taken on whichever pooled connection {@code JdbcTemplate} happened to hand out, the
 * checks would run on others, and the unlock would arrive on a fourth and return false. The lock
 * would then be held by an idle pooled connection until it was recycled, and every replica would
 * stop checking the invariant — silently, with the gauge frozen at its last value. The transaction
 * scope makes the release unconditional, which is the same reason the outbox relay uses it.
 *
 * <p>{@code Propagation.MANDATORY} on the inner method is deliberate: if the transaction is ever
 * lost to a proxy mistake the call fails loudly instead of taking a lock nothing will release.
 */
@Component
public class InvariantMonitor {

  private static final Logger log = LoggerFactory.getLogger(InvariantMonitor.class);

  /** Arbitrary but fixed: replicas must contend for the same lock to take turns. */
  static final long ADVISORY_LOCK_KEY = 0x7711CA1100000001L;

  private final JdbcTemplate jdbc;
  private final AtomicInteger violations = new AtomicInteger(-1);
  private final AtomicLong lastCheckedEpochSeconds = new AtomicLong();

  public InvariantMonitor(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
    this.jdbc = jdbc;

    // -1 until the first check completes. Zero would claim "no violations" before anything had
    // looked, which is the failure mode this project keeps running into.
    Gauge.builder("willcall.invariant.violations", violations, AtomicInteger::doubleValue)
        .description("Invariant checks currently failing; -1 means no check has completed yet")
        .register(meterRegistry);

    Gauge.builder(
            "willcall.invariant.checked.age.seconds",
            lastCheckedEpochSeconds,
            last ->
                last.get() == 0 ? Double.NaN : (System.currentTimeMillis() / 1000.0) - last.get())
        .description("Seconds since an invariant check last completed anywhere")
        .register(meterRegistry);
  }

  /**
   * Runs every check if this replica wins the lock.
   *
   * @return true if the checks ran here, false if another replica is doing it
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public boolean checkIfLockAcquired() {
    Boolean gotLock =
        jdbc.queryForObject(
            "select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
    if (!Boolean.TRUE.equals(gotLock)) return false;

    int failing = 0;
    for (Map.Entry<String, String> check : InvariantController.CHECKS.entrySet()) {
      Integer offenders =
          jdbc.queryForObject(
              "select count(*) from (" + check.getValue() + " limit 1) offenders", Integer.class);
      if (offenders != null && offenders > 0) {
        failing++;
        // ERROR with the check's name: this is the one alert in the system with no error budget,
        // and whoever is paged needs to know which check before they open a psql.
        log.error("invariant check failing: {}", check.getKey());
      }
    }
    violations.set(failing);
    lastCheckedEpochSeconds.set(System.currentTimeMillis() / 1000);
    return true;
  }

  /** For tests and for the age gauge's meaning: -1 means nothing has completed a check. */
  public int currentViolations() {
    return violations.get();
  }
}
