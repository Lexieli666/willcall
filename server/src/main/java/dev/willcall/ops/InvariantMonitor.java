package dev.willcall.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the invariant checks on a timer and publishes the result as a gauge.
 *
 * <p>{@code docs/slo.md} listed {@code WillcallOversellDetected} as a page-level alert, and the
 * alert could not fire: the invariant was checked by a script after a load run and by a POST
 * endpoint somebody had to call. An alert that depends on a person remembering to run something is
 * a checklist item wearing an alert's name. This is the metric the alert reads.
 *
 * <p>One replica per tick, through a session-scoped advisory lock. The capacity check scans every
 * event — 98 ms against 50,008 of them on the seeded dataset — and three replicas doing that on
 * their own schedules is three times the cost for the same answer. A replica that cannot get the
 * lock does nothing and leaves its gauge where it was, which is correct: the gauge reports the last
 * known state, and the {@code willcall_invariant_checked_age_seconds} companion is what says
 * whether anybody is still looking.
 */
@Component
public class InvariantMonitor {

  private static final Logger log = LoggerFactory.getLogger(InvariantMonitor.class);

  /** Arbitrary but fixed: two replicas must agree on which lock they are contending for. */
  private static final long ADVISORY_LOCK_KEY = 0x7711CA1100000001L;

  private final JdbcTemplate jdbc;
  private final boolean enabled;
  private final AtomicInteger violations = new AtomicInteger(-1);
  private final AtomicLong lastCheckedEpochSeconds = new AtomicLong();

  public InvariantMonitor(
      JdbcTemplate jdbc,
      MeterRegistry meterRegistry,
      @Value("${willcall.invariants.monitor-enabled:true}") boolean enabled) {
    this.jdbc = jdbc;
    this.enabled = enabled;

    // -1 until the first check completes. Zero would claim "no violations" before anything had
    // looked, which is the failure mode this whole project keeps running into.
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

  @Scheduled(
      fixedDelayString = "${willcall.invariants.interval-ms:60000}",
      initialDelayString = "${willcall.invariants.initial-delay-ms:15000}")
  public void check() {
    if (!enabled) return;
    try {
      Boolean gotLock =
          jdbc.queryForObject("select pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
      if (!Boolean.TRUE.equals(gotLock)) return;
      try {
        int failing = 0;
        for (Map.Entry<String, String> check : InvariantController.CHECKS.entrySet()) {
          Integer offenders =
              jdbc.queryForObject(
                  "select count(*) from (" + check.getValue() + " limit 1) offenders",
                  Integer.class);
          if (offenders != null && offenders > 0) {
            failing++;
            // ERROR with the check's name: this is the one alert in the system with no error
            // budget, and whoever is paged needs to know which check before they open a psql.
            log.error("invariant check failing: {}", check.getKey());
          }
        }
        violations.set(failing);
        lastCheckedEpochSeconds.set(System.currentTimeMillis() / 1000);
      } finally {
        jdbc.queryForObject("select pg_advisory_unlock(?)", Boolean.class, ADVISORY_LOCK_KEY);
      }
    } catch (RuntimeException e) {
      // Deliberately not resetting the gauge: a database that cannot be reached is not a database
      // with no violations, and the age gauge is what makes the difference visible.
      log.warn("invariant check could not run; the age gauge will rise", e);
    }
  }
}
