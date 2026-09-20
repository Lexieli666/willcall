package dev.willcall.reservation;

import static dev.willcall.support.InvariantAssertions.assertInvariantsHold;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.willcall.allocation.AllocationRequest;
import dev.willcall.catalog.domain.Event;
import dev.willcall.payment.FakePaymentGateway;
import dev.willcall.payment.PaymentBehavior;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import dev.willcall.reservation.domain.HoldGroup;
import dev.willcall.reservation.domain.Order;
import dev.willcall.reservation.service.CheckoutService;
import dev.willcall.reservation.service.IdempotencyService;
import dev.willcall.reservation.service.ReservationService;
import dev.willcall.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Retries must not multiply mutations.
 *
 * <p>The claim being tested is narrow and checkable: replaying the same {@code Idempotency-Key} one
 * hundred times produces exactly one hold and exactly one order, and the hundredth caller receives
 * the same answer as the first.
 */
class IdempotencyIntegrationTest extends IntegrationTestBase {

  @Autowired ReservationService reservations;
  @Autowired CheckoutService checkout;
  @Autowired IdempotencyService idempotency;
  @Autowired FakePaymentGateway gateway;

  private Event event;

  @BeforeEach
  void setUp() {
    gateway.reset();
    event = createEvent(2, 10, 300);
  }

  private record HoldOutcome(UUID holdId, List<UUID> seatIds) {}

  private IdempotencyService.Result<HoldOutcome> holdOnce(String buyer, String key, Object body) {
    return idempotency.execute(
        key,
        buyer,
        "POST /api/events/{eventId}/holds:" + event.id(),
        body,
        () -> {
          HoldGroup group =
              reservations.acquire(
                  event.id(), buyer, AllocationRequest.bestAvailable(event.id(), 2));
          return new IdempotencyService.Outcome<>(
              201, new HoldOutcome(group.id(), group.seatIds()), group.id());
        });
  }

  @Test
  @DisplayName("the same key replayed 100 times produces exactly one hold")
  void hundredReplaysProduceOneHold() {
    String key = "key-" + UUID.randomUUID();
    Map<String, Object> body = Map.of("quantity", 2);

    IdempotencyService.Result<HoldOutcome> first = holdOnce("buyer-0001", key, body);
    assertThat(first.replayed()).isFalse();
    UUID holdId = first.body().holdId();

    for (int i = 0; i < 99; i++) {
      IdempotencyService.Result<HoldOutcome> replay = holdOnce("buyer-0001", key, body);
      assertThat(replay.replayed()).as("attempt %s was replayed", i + 2).isTrue();
      assertThat(replay.status()).isEqualTo(201);
      assertThat(replay.replay().resourceId()).isEqualTo(holdId);
      assertThat(replay.replay().body()).contains(holdId.toString());
    }

    assertThat(jdbc.queryForObject("select count(*) from hold_groups", Long.class)).isEqualTo(1L);
    assertThat(countSeats("HELD")).isEqualTo(2);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("100 simultaneous replays of one key still produce exactly one hold")
  void concurrentReplaysProduceOneHold() throws Exception {
    String key = "key-" + UUID.randomUUID();
    Map<String, Object> body = Map.of("quantity", 2);

    int attempts = 100;
    AtomicInteger created = new AtomicInteger();
    AtomicInteger replayed = new AtomicInteger();
    AtomicInteger inProgress = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    Set<UUID> holdIds = ConcurrentHashMap.newKeySet();

    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(attempts);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < attempts; i++) {
        pool.submit(
            () -> {
              try {
                startGun.await();
                IdempotencyService.Result<HoldOutcome> result = holdOnce("buyer-0001", key, body);
                if (result.replayed()) {
                  replayed.incrementAndGet();
                  holdIds.add(result.replay().resourceId());
                } else {
                  created.incrementAndGet();
                  holdIds.add(result.body().holdId());
                }
              } catch (ApiException e) {
                // A concurrent retry that arrives while the first is still running is told to
                // wait rather than being queued behind it; that is the documented behaviour.
                if (e.code() == ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS)
                  inProgress.incrementAndGet();
                else unexpected.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unexpected.incrementAndGet();
              } catch (RuntimeException e) {
                unexpected.incrementAndGet();
              } finally {
                finished.countDown();
              }
            });
      }
      startGun.countDown();
      assertThat(finished.await(2, TimeUnit.MINUTES)).isTrue();
    }

    assertThat(unexpected.get()).isZero();
    assertThat(created.get()).as("exactly one caller did the work").isEqualTo(1);
    assertThat(replayed.get() + inProgress.get()).isEqualTo(attempts - 1);
    assertThat(holdIds).as("every caller that got an answer got the same hold").hasSize(1);

    assertThat(jdbc.queryForObject("select count(*) from hold_groups", Long.class)).isEqualTo(1L);
    assertThat(countSeats("HELD")).isEqualTo(2);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("reusing a key with a different body is 422, not somebody else's answer")
  void differentBodySameKeyIsRejected() {
    String key = "key-" + UUID.randomUUID();
    holdOnce("buyer-0001", key, Map.of("quantity", 2));

    assertThatThrownBy(() -> holdOnce("buyer-0001", key, Map.of("quantity", 4)))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);

    assertThat(jdbc.queryForObject("select count(*) from hold_groups", Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("the same key from a different buyer is a different key")
  void keysAreScopedToTheBuyer() {
    String key = "shared-key";
    holdOnce("buyer-0001", key, Map.of("quantity", 2));
    IdempotencyService.Result<HoldOutcome> other =
        holdOnce("buyer-0002", key, Map.of("quantity", 2));

    assertThat(other.replayed()).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from hold_groups", Long.class)).isEqualTo(2L);
    assertThat(countSeats("HELD")).isEqualTo(4);
  }

  @Test
  @DisplayName("field order and whitespace in the body do not make a retry look like a new request")
  void fingerprintIsCanonical() {
    String a =
        idempotency.fingerprint(
            "POST /api/orders", "buyer-0001", Map.of("holdId", "x", "paymentBehavior", "SUCCEED"));
    String b =
        idempotency.fingerprint(
            "POST /api/orders", "buyer-0001", Map.of("paymentBehavior", "SUCCEED", "holdId", "x"));

    assertThat(a).isEqualTo(b);
  }

  @Test
  @DisplayName("confirming twice with the same key charges once and returns the same order")
  void confirmIsIdempotent() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 2));
    String key = "confirm-" + UUID.randomUUID();
    Map<String, Object> body = Map.of("holdId", group.id().toString());

    List<UUID> orderIds = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      IdempotencyService.Result<UUID> result =
          idempotency.execute(
              key,
              "buyer-0001",
              "POST /api/orders",
              body,
              () -> {
                Order order = checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.SUCCEED);
                return new IdempotencyService.Outcome<>(201, order.id(), order.id());
              });
      orderIds.add(result.replayed() ? result.replay().resourceId() : result.body());
    }

    assertThat(orderIds).containsOnly(orderIds.get(0));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from orders where status = 'CONFIRMED'", Long.class))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject("select count(*) from order_lines", Long.class)).isEqualTo(2L);
    assertThat(countSeats("SOLD")).isEqualTo(2);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("a failed attempt releases its key, so a retry after seats free up can succeed")
  void failureReleasesTheKeyForARetry() {
    // A failure mutated nothing, so holding its key hostage would only prevent the retry that
    // the mechanism exists to make safe. Buyer two loses, seats free up, buyer two retries with
    // the same key and wins.
    HoldGroup blocking =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 20));
    String key = "loser-" + UUID.randomUUID();

    assertThatThrownBy(() -> holdOnce("buyer-0002", key, Map.of("quantity", 2)))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.SOLD_OUT);

    reservations.cancel(blocking.id(), "buyer-0001");

    IdempotencyService.Result<HoldOutcome> retry =
        holdOnce("buyer-0002", key, Map.of("quantity", 2));

    assertThat(retry.replayed()).isFalse();
    assertThat(retry.body().seatIds()).hasSize(2);
    assertThat(countSeats("HELD")).isEqualTo(2);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("a charge behind a timeout is completed by the retry rather than replayed as 504")
  void retryAfterGatewayTimeoutConfirmsTheCharge() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 1));
    String key = "timeout-" + UUID.randomUUID();
    Map<String, Object> body = Map.of("holdId", group.id().toString());

    java.util.function.Supplier<IdempotencyService.Result<UUID>> attempt =
        () ->
            idempotency.execute(
                key,
                "buyer-0001",
                "POST /api/orders",
                body,
                () -> {
                  Order order =
                      checkout.checkout(
                          group.id(), "buyer-0001", PaymentBehavior.SUCCEED_AFTER_TIMEOUT);
                  return new IdempotencyService.Outcome<>(201, order.id(), order.id());
                });

    assertThatThrownBy(attempt::get)
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.PAYMENT_TIMEOUT);

    IdempotencyService.Result<UUID> retry = attempt.get();

    assertThat(retry.replayed()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from orders where status = 'CONFIRMED'", Long.class))
        .isEqualTo(1L);
    assertThat(countSeats("SOLD")).isEqualTo(1);
    assertInvariantsHold(jdbc);
  }
}
