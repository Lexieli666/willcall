package dev.willcall.reservation;

import static dev.willcall.support.InvariantAssertions.assertInvariantsHold;
import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.allocation.AllocationRequest;
import dev.willcall.catalog.domain.Event;
import dev.willcall.payment.FakePaymentGateway;
import dev.willcall.payment.PaymentBehavior;
import dev.willcall.platform.web.ApiException;
import dev.willcall.reservation.domain.HoldGroup;
import dev.willcall.reservation.domain.Order;
import dev.willcall.reservation.service.CheckoutService;
import dev.willcall.reservation.service.HoldExpiryScheduler;
import dev.willcall.reservation.service.IdempotencyService;
import dev.willcall.reservation.service.ReservationService;
import dev.willcall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A hostile client and a hostile network, at the same time.
 *
 * <p>The scenario: many buyers, thirty per cent of every request duplicated, payments that decline
 * and time out at random, checkouts abandoned halfway through, and a sweeper running in the middle
 * of it. This is not a performance test; the only thing being asserted is that the database is
 * still internally consistent afterwards and that no seat was sold twice.
 *
 * <p>"Killing the app mid-checkout" is modelled by interrupting between prepare and settle, which
 * is exactly what a process death at that instant leaves behind: a PENDING order, an extended hold,
 * and possibly a charge at the gateway. The invariant has to survive that, and the seats have to
 * come back when the hold expires.
 */
class ChaosIntegrationTest extends IntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(ChaosIntegrationTest.class);

  @Autowired ReservationService reservations;
  @Autowired CheckoutService checkout;
  @Autowired IdempotencyService idempotency;
  @Autowired HoldExpiryScheduler sweeper;
  @Autowired FakePaymentGateway gateway;

  private Event event;

  @BeforeEach
  void setUp() {
    gateway.reset();
    event = createEvent(20, 10, 60);
  }

  @Test
  @DisplayName(
      "30% duplicated requests, random payment failures and abandoned checkouts leave the invariant intact")
  void chaosLeavesTheDatabaseConsistent() throws Exception {
    int buyers = 400;
    AtomicInteger confirmed = new AtomicInteger();
    AtomicInteger expectedFailures = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    AtomicInteger abandoned = new AtomicInteger();

    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(buyers);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < buyers; i++) {
        final String buyer = "buyer-%06d".formatted(i);
        pool.submit(
            () -> {
              try {
                startGun.await();
                runOneBuyer(buyer, confirmed, expectedFailures, abandoned);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unexpected.incrementAndGet();
              } catch (RuntimeException e) {
                unexpected.incrementAndGet();
                log.error("unexpected failure for {}", buyer, e);
              } finally {
                finished.countDown();
              }
            });
      }
      startGun.countDown();

      // The sweeper runs while the chaos is in flight, not politely afterwards.
      Thread sweeperThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (finished.getCount() > 0) {
                      try {
                        sweeper.drain();
                        Thread.sleep(20);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                      } catch (RuntimeException e) {
                        log.warn("sweeper failed mid-chaos", e);
                      }
                    }
                  });

      assertThat(finished.await(5, TimeUnit.MINUTES)).isTrue();
      sweeperThread.join(TimeUnit.SECONDS.toMillis(30));
    }

    log.info(
        "chaos complete: confirmed={} expectedFailures={} abandoned={} unexpected={}",
        confirmed.get(),
        expectedFailures.get(),
        abandoned.get(),
        unexpected.get());

    assertThat(unexpected.get()).as("unexpected failures").isZero();
    assertInvariantsHold(jdbc);

    // Every seat that ended up SOLD is on exactly one confirmed order line, and no seat is on two.
    Long duplicated =
        jdbc.queryForObject(
            "select count(*) from (select seat_id from order_lines group by seat_id having count(*) > 1) d",
            Long.class);
    assertThat(duplicated).as("seats appearing on more than one order line").isZero();

    // Abandoned checkouts must not leak capacity: expire everything and confirm the seats return.
    jdbc.update(
        "update holds set expires_at = now() - interval '1 second' where status = 'ACTIVE'");
    jdbc.update(
        "update hold_groups set expires_at = now() - interval '1 second' where status = 'ACTIVE'");
    sweeper.drain();

    long sold = countSeats("SOLD");
    long available = countSeats("AVAILABLE");
    assertThat(countSeats("HELD")).as("nothing is still held after a full sweep").isZero();
    assertThat(sold + available).isEqualTo(200L);
    assertInvariantsHold(jdbc);
  }

  private void runOneBuyer(
      String buyer,
      AtomicInteger confirmed,
      AtomicInteger expectedFailures,
      AtomicInteger abandoned) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    String holdKey = "hold-" + UUID.randomUUID();
    int quantity = 1 + random.nextInt(2);

    UUID holdId;
    try {
      holdId = holdWithRetries(buyer, holdKey, quantity, random);
    } catch (ApiException e) {
      expectedFailures.incrementAndGet();
      return;
    }
    if (holdId == null) {
      expectedFailures.incrementAndGet();
      return;
    }

    // One buyer in six walks away after holding. Their seats must come back on their own.
    if (random.nextInt(6) == 0) {
      abandoned.incrementAndGet();
      return;
    }

    PaymentBehavior behavior =
        switch (random.nextInt(10)) {
          case 0, 1 -> PaymentBehavior.DECLINE;
          case 2 -> PaymentBehavior.TIMEOUT;
          case 3 -> PaymentBehavior.SUCCEED_AFTER_TIMEOUT;
          default -> PaymentBehavior.SUCCEED;
        };

    String confirmKey = "confirm-" + UUID.randomUUID();
    Map<String, Object> body =
        Map.of("holdId", holdId.toString(), "paymentBehavior", behavior.name());

    // Every request is sent twice 30% of the time, the way an impatient client on a bad
    // connection behaves. Both copies carry the same key.
    int attempts = random.nextInt(10) < 3 ? 2 : 1;
    for (int attempt = 0; attempt < attempts; attempt++) {
      try {
        IdempotencyService.Result<UUID> result =
            idempotency.execute(
                confirmKey,
                buyer,
                "POST /api/orders",
                body,
                () -> {
                  Order order = checkout.checkout(holdId, buyer, behavior);
                  return new IdempotencyService.Outcome<>(201, order.id(), order.id());
                });
        if (!result.replayed()) confirmed.incrementAndGet();
      } catch (ApiException e) {
        expectedFailures.incrementAndGet();
      }
    }
  }

  private UUID holdWithRetries(String buyer, String key, int quantity, ThreadLocalRandom random) {
    Map<String, Object> body = Map.of("quantity", quantity);
    int attempts = random.nextInt(10) < 3 ? 2 : 1;
    UUID holdId = null;
    for (int attempt = 0; attempt < attempts; attempt++) {
      IdempotencyService.Result<UUID> result =
          idempotency.execute(
              key,
              buyer,
              "POST /api/events/{eventId}/holds:" + event.id(),
              body,
              () -> {
                HoldGroup group =
                    reservations.acquire(
                        event.id(), buyer, AllocationRequest.bestAvailable(event.id(), quantity));
                return new IdempotencyService.Outcome<>(201, group.id(), group.id());
              });
      holdId = result.replayed() ? result.replay().resourceId() : result.body();
    }
    return holdId;
  }

  @Test
  @DisplayName(
      "a checkout abandoned between prepare and settle releases its seats when the hold expires")
  void abandonedCheckoutDoesNotLeakCapacity() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 3));

    // Exactly what a process death after prepare leaves behind: a PENDING order and an extended
    // hold, with nothing ever settling it.
    ReservationService.CheckoutDraft draft = reservations.prepareCheckout(group.id(), "buyer-0001");
    assertThat(draft.orderId()).isNotNull();
    assertThat(countSeats("HELD")).isEqualTo(3);

    jdbc.update(
        "update holds set expires_at = now() - interval '1 second' where status = 'ACTIVE'");
    jdbc.update(
        "update hold_groups set expires_at = now() - interval '1 second' where status = 'ACTIVE'");
    sweeper.drain();

    assertThat(countSeats("HELD")).isZero();
    assertThat(countSeats("SOLD")).isZero();
    assertThat(
            jdbc.queryForObject("select count(*) from orders where status = 'PENDING'", Long.class))
        .as("the abandoned order is still there as an audit trail")
        .isEqualTo(1L);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName(
      "the invariant SQL in the admin endpoint matches the shell script byte for byte in intent")
  void adminChecksCoverEverythingTheScriptChecks() {
    // The shell script is the authority because it runs without the application. This asserts the
    // in-process copy has not fallen behind: the script has six checks plus the capacity check,
    // and every name below must be represented there.
    assertThat(dev.willcall.ops.InvariantController.CHECKS.keySet())
        .containsExactlyInAnyOrder(
            "confirmed_plus_held_within_capacity",
            "one_active_hold_per_seat",
            "no_seat_both_sold_and_held",
            "sold_seat_has_one_confirmed_line",
            "held_seat_has_active_hold",
            "active_hold_points_at_held_seat",
            "capacity_matches_sellable_seats");

    for (Map.Entry<String, String> check : dev.willcall.ops.InvariantController.CHECKS.entrySet()) {
      List<String> rows = jdbc.queryForList(check.getValue(), String.class);
      assertThat(rows)
          .as("check %s runs and returns no rows on an empty database", check.getKey())
          .isEmpty();
    }
  }
}
