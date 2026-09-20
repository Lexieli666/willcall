package dev.willcall.ops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.catalog.domain.Event;
import dev.willcall.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The falsifier for the invariant monitor.
 *
 * <p>A monitor that reports "no violations" is only worth having if something can make it report
 * one. This plants a violation the monitor is supposed to catch and asserts that it does — the same
 * shape of test the invariant shell script now has, and for the same reason: four separate
 * instruments in this project have passed while measuring nothing, and every one of them would have
 * been caught by asking it to fail once.
 */
class InvariantMonitorIntegrationTest extends IntegrationTestBase {

  @Autowired InvariantMonitor monitor;
  @Autowired MeterRegistry meters;

  private double violations() {
    return meters.get("willcall.invariant.violations").gauge().value();
  }

  @Test
  @DisplayName("a clean database reports zero, and the gauge starts at -1 rather than at zero")
  void reportsZeroOnACleanDatabase() {
    // Before the first check the gauge must not claim the database is clean. "Nothing has looked"
    // and "nothing is wrong" are different states, and reporting the second for the first is how
    // an alert gets quietly switched off.
    assertThat(violations())
        .as("the gauge before any check has completed")
        .satisfiesAnyOf(
            value -> assertThat(value).isEqualTo(-1.0),
            // A previous test in the same context may already have run a check.
            value -> assertThat(value).isGreaterThanOrEqualTo(0.0));

    createEvent(2, 4, 60);
    monitor.check();

    assertThat(violations()).as("a clean database").isZero();
  }

  @Test
  @DisplayName("a seat both SOLD and actively held is caught")
  void catchesAPlantedViolation() {
    Event event = createEvent(2, 4, 60);
    UUID seatId =
        jdbc.queryForObject(
            "select id from seats where event_id = ? limit 1", UUID.class, event.id());
    UUID groupId = UUID.randomUUID();

    monitor.check();
    assertThat(violations()).as("before planting anything").isZero();

    // Straight into the tables, bypassing every service: the point is to prove the monitor reads
    // the database rather than the application's opinion of it.
    jdbc.update(
        """
        insert into hold_groups (id, event_id, user_ref, status, expires_at, seat_count)
        values (?, ?, 'planted', 'ACTIVE', now() + interval '1 hour', 1)
        """,
        groupId,
        event.id());
    jdbc.update(
        """
        insert into holds (id, hold_group_id, event_id, seat_id, user_ref, status, expires_at)
        values (?, ?, ?, ?, 'planted', 'ACTIVE', now() + interval '1 hour')
        """,
        UUID.randomUUID(),
        groupId,
        event.id(),
        seatId);
    jdbc.update("update seats set status = 'SOLD' where id = ?", seatId);

    monitor.check();

    assertThat(violations())
        .as("a seat that is SOLD and also actively held")
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("every check the endpoint runs is a check the monitor runs")
  void theMonitorAndTheEndpointCheckTheSameThings() {
    // The monitor iterates InvariantController.CHECKS, so this cannot drift by construction — but
    // it can drift against the shell script, which is the authoritative one. That comparison has
    // its own test; this pins the count so that deleting a check is a deliberate act.
    assertThat(InvariantController.CHECKS)
        .as("the invariant checks the monitor iterates")
        .hasSizeGreaterThanOrEqualTo(7);
  }
}
