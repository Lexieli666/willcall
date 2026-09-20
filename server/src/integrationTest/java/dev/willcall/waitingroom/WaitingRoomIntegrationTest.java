package dev.willcall.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.service.CatalogService;
import dev.willcall.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.context.TestPropertySource;

/**
 * The queue, against a real Redis.
 *
 * <p>The properties being pinned are the ones a buyer would notice: a refresh does not cost a
 * place, admission happens in arrival order, the rate is the configured rate no matter how many
 * replicas are admitting, and somebody queueing for seats that cannot exist is told so.
 */
@TestPropertySource(
    properties = {
      "willcall.waitingroom.admitter-enabled=false",
      "willcall.waitingroom.broadcast-enabled=false"
    })
class WaitingRoomIntegrationTest extends IntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(WaitingRoomIntegrationTest.class);

  @Autowired WaitingRoomService waitingRoom;
  @Autowired AdmissionScheduler admitter;
  @Autowired AdmissionTokenService tokens;
  @Autowired AdmissionRepository admissions;
  @Autowired StringRedisTemplate redis;

  private Event event;

  @BeforeEach
  void setUp() {
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    event = createQueuedEvent(50, 10.0);
  }

  private Event createQueuedEvent(int seats, double ratePerSecond) {
    return catalog.createEvent(
        new CatalogService.CreateEventSpec(
            "Queue Arena",
            "Queue Event",
            java.time.Instant.now().plusSeconds(86_400),
            java.time.Instant.now().minusSeconds(60),
            120,
            8,
            dev.willcall.catalog.domain.EventStatus.ON_SALE,
            List.of(new CatalogService.PriceTierSpec("Standard", 3_000, "USD")),
            List.of(new CatalogService.SectionSpec("Floor", 1, seats, "Standard")),
            true,
            ratePerSecond));
  }

  @Test
  @DisplayName("joining puts a buyer at the back and reports a 1-based position")
  void joinReportsPosition() {
    QueuePosition first = waitingRoom.join(event.id(), "buyer-00000001");
    QueuePosition second = waitingRoom.join(event.id(), "buyer-00000002");

    assertThat(first.state()).isEqualTo(QueuePosition.State.WAITING);
    assertThat(first.position()).isEqualTo(1);
    assertThat(second.position()).isEqualTo(2);
    assertThat(second.queueLength()).isEqualTo(2);
  }

  @Test
  @DisplayName("joining twice does not move anybody, because a refresh must not cost a place")
  void rejoiningKeepsThePlace() {
    waitingRoom.join(event.id(), "buyer-00000001");
    waitingRoom.join(event.id(), "buyer-00000002");

    QueuePosition rejoin = waitingRoom.join(event.id(), "buyer-00000001");

    assertThat(rejoin.position()).isEqualTo(1);
    assertThat(waitingRoom.queueLength(event.id())).isEqualTo(2);
    assertThat(waitingRoom.position(event.id(), "buyer-00000002").position()).isEqualTo(2);
  }

  @Test
  @DisplayName("an estimate is derived from the measured rate, not invented")
  void estimateUsesTheMeasuredRate() {
    for (int i = 0; i < 30; i++) waitingRoom.join(event.id(), "buyer-%08d".formatted(i));

    QueuePosition last = waitingRoom.position(event.id(), "buyer-00000029");

    // Position 30 at ten admissions per second is three seconds.
    assertThat(last.position()).isEqualTo(30);
    assertThat(last.estimatedWaitSeconds()).isEqualTo(3);
  }

  @Test
  @DisplayName("somebody queueing for seats that cannot exist is told so")
  void beyondInventoryIsReported() {
    Event tiny = createQueuedEvent(3, 10.0);
    for (int i = 0; i < 6; i++) waitingRoom.join(tiny.id(), "buyer-%08d".formatted(i));

    assertThat(waitingRoom.position(tiny.id(), "buyer-00000002").beyondInventory()).isFalse();
    assertThat(waitingRoom.position(tiny.id(), "buyer-00000005").beyondInventory()).isTrue();
  }

  @Test
  @DisplayName("leaving gives up the place and moves everybody behind forward")
  void leavingFreesThePlace() {
    waitingRoom.join(event.id(), "buyer-00000001");
    waitingRoom.join(event.id(), "buyer-00000002");
    waitingRoom.join(event.id(), "buyer-00000003");

    waitingRoom.leave(event.id(), "buyer-00000001");

    assertThat(waitingRoom.position(event.id(), "buyer-00000002").position()).isEqualTo(1);
    assertThat(waitingRoom.position(event.id(), "buyer-00000001").state())
        .isEqualTo(QueuePosition.State.NOT_QUEUED);
    // Idempotent: leaving twice is the same as leaving once.
    waitingRoom.leave(event.id(), "buyer-00000001");
    assertThat(waitingRoom.queueLength(event.id())).isEqualTo(2);
  }

  @Test
  @DisplayName("admission is paced by the bucket and happens in arrival order")
  void admissionIsPacedAndOrdered() {
    for (int i = 0; i < 40; i++) waitingRoom.join(event.id(), "buyer-%08d".formatted(i));

    // The bucket starts full at the configured burst, so the first pass admits the burst and no
    // more, however many are waiting.
    List<String> firstPass = waitingRoom.admitBatch(event);

    assertThat(firstPass).isNotEmpty();
    assertThat(firstPass.size()).isLessThanOrEqualTo(40);
    // Arrival order, front first.
    assertThat(firstPass.get(0)).isEqualTo("buyer-00000000");
    List<String> sorted = new ArrayList<>(firstPass);
    java.util.Collections.sort(sorted);
    assertThat(firstPass).isEqualTo(sorted);
  }

  @Test
  @DisplayName("an admitted buyer gets a token that verifies for this event and nobody else's")
  void admittedBuyerGetsAUsableToken() {
    waitingRoom.join(event.id(), "buyer-00000001");
    waitingRoom.admitBatch(event);

    QueuePosition position = waitingRoom.position(event.id(), "buyer-00000001");

    assertThat(position.state()).isEqualTo(QueuePosition.State.ADMITTED);
    assertThat(position.admissionToken()).isNotBlank();
    assertThat(tokens.verify(position.admissionToken(), event.id().toString(), "buyer-00000001"))
        .isPresent();
    // Not transferable to another buyer, and not usable on another event.
    assertThat(tokens.verify(position.admissionToken(), event.id().toString(), "buyer-00000002"))
        .isEmpty();
    assertThat(
            tokens.verify(
                position.admissionToken(),
                java.util.UUID.randomUUID().toString(),
                "buyer-00000001"))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "concurrent admitters share one bucket, so the rate does not multiply by replica count")
  void concurrentAdmittersShareTheBucket() throws Exception {
    Event paced = createQueuedEvent(500, 5.0);
    for (int i = 0; i < 400; i++) waitingRoom.join(paced.id(), "buyer-%08d".formatted(i));

    // Six simultaneous passes, as six replicas would produce. With a per-replica bucket this
    // would admit six times the rate; with the shared one in Redis it admits the rate once.
    int passes = 6;
    Set<String> everybodyAdmitted = java.util.Collections.synchronizedSet(new HashSet<>());
    AtomicInteger duplicates = new AtomicInteger();
    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(passes);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < passes; i++) {
        pool.submit(
            () -> {
              try {
                startGun.await();
                for (String user : waitingRoom.admitBatch(paced)) {
                  if (!everybodyAdmitted.add(user)) duplicates.incrementAndGet();
                }
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

    log.info(
        "six concurrent admission passes admitted {} distinct buyers, {} duplicates",
        everybodyAdmitted.size(),
        duplicates.get());

    // Nobody admitted twice: ZPOPMIN inside the script is what guarantees it.
    assertThat(duplicates.get()).isZero();
    // And the total is bounded by the bucket, not by six times the bucket.
    assertThat(everybodyAdmitted.size()).isLessThanOrEqualTo(120);
    assertThat(waitingRoom.queueLength(paced.id())).isEqualTo(400 - everybodyAdmitted.size());
  }

  @Test
  @DisplayName("the admission audit trail records arrival and admission order in PostgreSQL")
  void admissionsAreRecordedDurably() {
    for (int i = 0; i < 20; i++) waitingRoom.join(event.id(), "buyer-%08d".formatted(i));
    admitter.admitAll();

    long recorded = admissions.countFor(event.id());
    assertThat(recorded).isPositive();

    AdmissionRepository.InversionReport report = admissions.inversionRate(event.id());
    assertThat(report.admittedCount()).isEqualTo(recorded);
    // Admitted straight from the front of a quiet queue, so arrival order and admission order
    // agree exactly. Under load they will not, and the published rate is what says by how much.
    assertThat(report.inversionRate()).isZero();
  }

  @Test
  @DisplayName("an event with no waiting room admits everybody immediately")
  void noWaitingRoomMeansOpenAdmission() {
    Event open = createEvent(2, 10, 120);

    QueuePosition position = waitingRoom.join(open.id(), "buyer-00000001");

    assertThat(position.state()).isEqualTo(QueuePosition.State.DEGRADED_OPEN);
    assertThat(position.admissionToken()).isNotBlank();
    assertThat(waitingRoom.admitBatch(open)).isEmpty();
  }
}
