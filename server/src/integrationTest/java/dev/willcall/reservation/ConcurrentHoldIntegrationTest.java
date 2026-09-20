package dev.willcall.reservation;

import static dev.willcall.support.InvariantAssertions.assertInvariantsHold;
import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.allocation.AllocationRequest;
import dev.willcall.catalog.domain.Event;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import dev.willcall.reservation.service.ReservationService;
import dev.willcall.support.IntegrationTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The headline correctness claim: ten thousand buyers, five hundred seats, no oversells.
 *
 * <h2>How this is built so that it can actually fail</h2>
 *
 * <ul>
 *   <li><b>Every attempt asks for "any one seat", not a named one.</b> With named seats the
 *       expected success count would depend on which seats the random generator happened to pick.
 *       With "best available" the arithmetic is exact: 500 successes, 9,500 rejections, always.
 *   <li><b>All ten thousand threads wait on one latch and are released together.</b> Ramping them
 *       up would spread the contention out and the test would pass without ever exercising it.
 *   <li><b>Virtual threads.</b> Ten thousand platform threads on a CI runner is a test of the
 *       scheduler; virtual threads make the contention happen in PostgreSQL, which is where the
 *       claim lives.
 *   <li><b>Unexpected exceptions are counted separately from 409s.</b> "Zero oversells" is
 *       worthless if it was achieved by five hundred requests failing with a 500, so the assertion
 *       is on all three numbers at once.
 *   <li><b>The invariant is checked against the database afterwards</b>, not against the service's
 *       own bookkeeping.
 * </ul>
 *
 * <p>Five runs in CI, fifty in long mode ({@code -Dwillcall.longMode=true}). A race that shows up
 * one run in twenty is still a race, and one run would not find it.
 */
class ConcurrentHoldIntegrationTest extends IntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(ConcurrentHoldIntegrationTest.class);

  private static final int SEATS = 500;
  private static final int ATTEMPTS = 10_000;

  @Autowired ReservationService reservations;

  private static int runCount() {
    return Boolean.getBoolean("willcall.longMode") ? 50 : 5;
  }

  @Test
  @DisplayName(
      "10,000 concurrent single-seat holds against 500 seats: exactly 500 win, 9,500 get 409, 0 errors")
  void tenThousandConcurrentHoldsAgainstFiveHundredSeats() throws Exception {
    int runs = runCount();
    List<String> summaries = new ArrayList<>(runs);

    for (int run = 1; run <= runs; run++) {
      truncateEverything();
      Event event = createEvent(10, SEATS / 10, 600);

      AtomicInteger granted = new AtomicInteger();
      AtomicInteger rejected = new AtomicInteger();
      AtomicInteger unexpected = new AtomicInteger();
      Map<String, AtomicInteger> rejectionCodes = new ConcurrentHashMap<>();

      CountDownLatch startGun = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(ATTEMPTS);

      long startedAt;
      try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < ATTEMPTS; i++) {
          final String buyer = "buyer-%06d".formatted(i);
          pool.submit(
              () -> {
                try {
                  startGun.await();
                  reservations.acquire(
                      event.id(), buyer, AllocationRequest.bestAvailable(event.id(), 1));
                  granted.incrementAndGet();
                } catch (ApiException e) {
                  if (e.code() == ErrorCode.SOLD_OUT || e.code() == ErrorCode.SEAT_UNAVAILABLE) {
                    rejected.incrementAndGet();
                  } else {
                    unexpected.incrementAndGet();
                  }
                  rejectionCodes
                      .computeIfAbsent(e.code().wireCode(), k -> new AtomicInteger())
                      .incrementAndGet();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  unexpected.incrementAndGet();
                } catch (RuntimeException e) {
                  unexpected.incrementAndGet();
                  log.error("unexpected failure on attempt for {}", buyer, e);
                } finally {
                  finished.countDown();
                }
              });
        }

        startedAt = System.nanoTime();
        startGun.countDown();
        boolean allDone = finished.await(5, TimeUnit.MINUTES);
        assertThat(allDone).as("all %s attempts finished", ATTEMPTS).isTrue();
      }
      Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

      long heldInDatabase = countSeats("HELD");
      long availableInDatabase = countSeats("AVAILABLE");

      String summary =
          "run %d/%d: granted=%d rejected=%d unexpected=%d held=%d available=%d in %d ms, codes=%s"
              .formatted(
                  run,
                  runs,
                  granted.get(),
                  rejected.get(),
                  unexpected.get(),
                  heldInDatabase,
                  availableInDatabase,
                  elapsed.toMillis(),
                  rejectionCodes.entrySet().stream()
                      .collect(
                          java.util.stream.Collectors.toMap(
                              Map.Entry::getKey, e -> e.getValue().get())));
      log.info(summary);
      summaries.add(summary);

      assertThat(unexpected.get()).as("unexpected failures in %s", summary).isZero();
      assertThat(granted.get()).as("successful holds in %s", summary).isEqualTo(SEATS);
      assertThat(rejected.get()).as("clean rejections in %s", summary).isEqualTo(ATTEMPTS - SEATS);
      assertThat(heldInDatabase).as("seats HELD in the database in %s", summary).isEqualTo(SEATS);
      assertThat(availableInDatabase).as("seats AVAILABLE in %s", summary).isZero();

      assertInvariantsHold(jdbc);
    }

    log.info("concurrency suite complete:\n{}", String.join("\n", summaries));
  }

  @Test
  @DisplayName("concurrent requests for the same exact seats: one wins, the rest get a clean 409")
  void concurrentExactSeatRequestsForTheSameSeats() throws Exception {
    Event event = createEvent(1, 10, 600);
    List<java.util.UUID> contested =
        jdbc.queryForList(
            "select id from seats where event_id = ? order by seat_number limit 3",
            java.util.UUID.class,
            event.id());

    int contenders = 200;
    AtomicInteger granted = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(contenders);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < contenders; i++) {
        final String buyer = "buyer-%06d".formatted(i);
        pool.submit(
            () -> {
              try {
                startGun.await();
                reservations.acquire(
                    event.id(), buyer, AllocationRequest.exact(event.id(), contested));
                granted.incrementAndGet();
              } catch (ApiException e) {
                if (e.code() == ErrorCode.SEAT_UNAVAILABLE) rejected.incrementAndGet();
                else unexpected.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unexpected.incrementAndGet();
              } catch (RuntimeException e) {
                unexpected.incrementAndGet();
                log.error("unexpected failure", e);
              } finally {
                finished.countDown();
              }
            });
      }
      startGun.countDown();
      assertThat(finished.await(2, TimeUnit.MINUTES)).isTrue();
    }

    assertThat(unexpected.get()).isZero();
    assertThat(granted.get()).as("exactly one buyer gets the block").isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(contenders - 1);
    assertThat(countSeats("HELD")).isEqualTo(3);
    assertInvariantsHold(jdbc);
  }
}
