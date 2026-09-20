package dev.willcall.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.allocation.AllocationRequest;
import dev.willcall.catalog.domain.Event;
import dev.willcall.reservation.service.OutboxRelay;
import dev.willcall.reservation.service.OutboxScheduler;
import dev.willcall.reservation.service.ReservationService;
import dev.willcall.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Sequence numbers, which the whole real-time protocol rests on.
 *
 * <p>If they are not contiguous, every client resyncs constantly. If they are not monotonic,
 * clients apply stale state. If two replicas can assign the same number, two clients disagree about
 * what happened. Each of those is tested here rather than assumed from the design.
 */
class OutboxRelayIntegrationTest extends IntegrationTestBase {

  @Autowired ReservationService reservations;
  @Autowired OutboxRelay relay;
  @Autowired OutboxScheduler scheduler;

  private Event event;
  private final List<OutboxRelay.PublishedEntry> published = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    event = createEvent(10, 20, 300);
    published.clear();
    relay.subscribe(published::add);
  }

  @Test
  @DisplayName("sequence numbers are contiguous from one, with no gaps")
  void sequenceIsContiguous() {
    for (int i = 0; i < 20; i++) {
      reservations.acquire(
          event.id(), "buyer-%06d".formatted(i), AllocationRequest.bestAvailable(event.id(), 1));
    }

    scheduler.drain();

    List<Long> sequences =
        published.stream().map(OutboxRelay.PublishedEntry::sequenceNo).sorted().toList();
    assertThat(sequences).hasSize(20);
    for (int i = 0; i < sequences.size(); i++) {
      // Contiguity is what makes gap detection possible at all: a client holding N that receives
      // N+2 must be able to conclude one message is missing rather than guessing.
      assertThat(sequences.get(i)).as("sequence at position %s", i).isEqualTo(i + 1L);
    }
  }

  @Test
  @DisplayName("sequence numbers survive a second pass without restarting or repeating")
  void sequenceContinuesAcrossPasses() {
    reservations.acquire(
        event.id(), "buyer-000001", AllocationRequest.bestAvailable(event.id(), 3));
    scheduler.drain();
    reservations.acquire(
        event.id(), "buyer-000002", AllocationRequest.bestAvailable(event.id(), 2));
    scheduler.drain();

    List<Long> sequences =
        published.stream().map(OutboxRelay.PublishedEntry::sequenceNo).sorted().toList();
    assertThat(sequences).containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  @DisplayName("concurrent relays never assign the same number twice")
  void concurrentRelaysDoNotCollide() throws Exception {
    for (int i = 0; i < 40; i++) {
      reservations.acquire(
          event.id(), "buyer-%06d".formatted(i), AllocationRequest.bestAvailable(event.id(), 1));
    }

    // Eight simultaneous passes, as eight replicas would produce. The per-event advisory lock is
    // what keeps them from interleaving; without it, numbers would be assigned out of order and
    // every connected client would see a gap.
    int passes = 8;
    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(passes);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < passes; i++) {
        pool.submit(
            () -> {
              try {
                startGun.await();
                relay.publishBatchFor(event.id());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                finished.countDown();
              }
            });
      }
      startGun.countDown();
      assertThat(finished.await(1, TimeUnit.MINUTES)).isTrue();
    }

    List<Long> sequences =
        new ArrayList<>(published.stream().map(OutboxRelay.PublishedEntry::sequenceNo).toList());
    assertThat(sequences).doesNotHaveDuplicates();
    assertThat(sequences).hasSize(40);
  }

  @Test
  @DisplayName("each published entry carries the commit time, so propagation can be measured")
  void commitTimeIsCarried() {
    reservations.acquire(
        event.id(), "buyer-000001", AllocationRequest.bestAvailable(event.id(), 1));
    scheduler.drain();

    assertThat(published).hasSize(1);
    assertThat(published.get(0).committedAt())
        .as(
            "without this, propagation could only be measured from the fan-out, which excludes "
                + "the relay's own queueing")
        .isNotNull();
  }

  @Test
  @DisplayName("a fan-out that throws does not stall the sequence for everybody else")
  void aFailingSubscriberDoesNotBlockTheRelay() {
    relay.subscribe(
        entry -> {
          throw new IllegalStateException("the fan-out is unhappy");
        });

    reservations.acquire(
        event.id(), "buyer-000001", AllocationRequest.bestAvailable(event.id(), 2));
    int drained = scheduler.drain();

    // The entries are still marked published and the sequence still advanced: a client that
    // misses a delta detects the gap and resyncs, which is far better than the whole event's
    // stream stopping because one subscriber threw.
    assertThat(drained).isEqualTo(2);
    Long unpublished =
        jdbc.queryForObject("select count(*) from outbox where published_at is null", Long.class);
    assertThat(unpublished).isZero();
  }

  @Test
  @DisplayName("nothing is published twice, even if the relay runs repeatedly")
  void publishingIsIdempotent() {
    reservations.acquire(
        event.id(), "buyer-000001", AllocationRequest.bestAvailable(event.id(), 3));

    scheduler.drain();
    scheduler.drain();
    scheduler.drain();

    assertThat(published).hasSize(3);
  }
}
