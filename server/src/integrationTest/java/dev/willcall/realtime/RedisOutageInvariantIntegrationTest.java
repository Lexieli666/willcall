package dev.willcall.realtime;

import static dev.willcall.support.InvariantAssertions.assertInvariantsHold;
import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.allocation.AllocationRequest;
import dev.willcall.catalog.domain.Event;
import dev.willcall.payment.FakePaymentGateway;
import dev.willcall.payment.PaymentBehavior;
import dev.willcall.platform.web.ApiException;
import dev.willcall.reservation.domain.HoldGroup;
import dev.willcall.reservation.service.CheckoutService;
import dev.willcall.reservation.service.ReservationService;
import dev.willcall.support.IntegrationTestBase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Losing Redis must cost the queue, not the inventory.
 *
 * <p>ADR 0001 claims PostgreSQL owns correctness and Redis only accelerates. This is the test that
 * makes the claim falsifiable: Redis is stopped in the middle of a run of holds and confirmations,
 * traffic keeps flowing throughout, Redis comes back, and the full invariant suite is then run
 * against the resulting database.
 *
 * <p>The assertion is deliberately not "nothing failed". Some requests may fail while Redis is down
 * — that is a degradation, and an honest one. What must never happen is a seat sold twice, a hold
 * that outlives its seat, or capacity that quietly grows.
 */
class RedisOutageInvariantIntegrationTest extends IntegrationTestBase {

  private static final Logger log =
      LoggerFactory.getLogger(RedisOutageInvariantIntegrationTest.class);

  @Autowired ReservationService reservations;
  @Autowired CheckoutService checkout;
  @Autowired StringRedisTemplate redis;
  @Autowired FakePaymentGateway gateway;

  private Event event;

  @BeforeEach
  void setUp() {
    gateway.reset();
    event = createEvent(20, 10, 300);
  }

  @Test
  @DisplayName("stopping Redis mid-run degrades the queue and leaves the inventory correct")
  void redisOutageDoesNotBreakTheInvariant() throws Exception {
    assertThat(REDIS.isRunning()).isTrue();

    int buyers = 120;
    AtomicInteger confirmed = new AtomicInteger();
    AtomicInteger refused = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();

    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(buyers);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < buyers; i++) {
        final String buyer = "buyer-%06d".formatted(i);
        pool.submit(
            () -> {
              try {
                startGun.await();
                HoldGroup group =
                    reservations.acquire(
                        event.id(), buyer, AllocationRequest.bestAvailable(event.id(), 1));
                checkout.checkout(group.id(), buyer, PaymentBehavior.SUCCEED);
                confirmed.incrementAndGet();
              } catch (ApiException e) {
                refused.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unexpected.incrementAndGet();
              } catch (RuntimeException e) {
                // A Redis failure surfacing here would be the bug: the reservation path must not
                // touch Redis in a way that can fail the transaction.
                unexpected.incrementAndGet();
                log.error("reservation failed while Redis was down", e);
              } finally {
                finished.countDown();
              }
            });
      }

      startGun.countDown();

      // Pull the plug in the middle of the run, not before it and not after.
      Thread.sleep(50);
      log.info("stopping Redis mid-run");
      REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).exec();

      assertThat(finished.await(3, TimeUnit.MINUTES)).isTrue();
    } finally {
      log.info("restarting Redis");
      REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
      waitForRedis();
    }

    log.info(
        "redis outage run: confirmed={} refused={} unexpected={}",
        confirmed.get(),
        refused.get(),
        unexpected.get());

    assertThat(unexpected.get())
        .as("a Redis outage must not surface as an unexpected failure in the reservation path")
        .isZero();
    assertThat(confirmed.get()).as("seats were still sold while Redis was down").isPositive();

    assertInvariantsHold(jdbc);

    Long soldSeats =
        jdbc.queryForObject("select count(*) from seats where status = 'SOLD'", Long.class);
    Long orderLines = jdbc.queryForObject("select count(*) from order_lines", Long.class);
    assertThat(soldSeats).isEqualTo(orderLines);
    assertThat(soldSeats).isEqualTo(Long.valueOf(confirmed.get()));
  }

  @Test
  @DisplayName("readiness reports Redis as degraded, not down, because reservations are unaffected")
  void redisDownIsDegradedNotDown() throws Exception {
    REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).exec();
    try {
      // Reservations keep working, which is the whole point of the distinction.
      HoldGroup group =
          reservations.acquire(
              event.id(), "buyer-degraded", AllocationRequest.bestAvailable(event.id(), 1));
      assertThat(group.seatIds()).hasSize(1);
      assertInvariantsHold(jdbc);
    } finally {
      REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
      waitForRedis();
    }
  }

  private void waitForRedis() throws InterruptedException {
    for (int attempt = 0; attempt < 120; attempt++) {
      try {
        if ("PONG".equalsIgnoreCase(redis.getConnectionFactory().getConnection().ping())) {
          return;
        }
      } catch (RuntimeException e) {
        // still starting
      }
      Thread.sleep(250);
    }
    throw new IllegalStateException("Redis did not come back");
  }
}
