package dev.willcall.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.WillcallApplication;
import dev.willcall.allocation.AllocationRequest;
import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.domain.EventStatus;
import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.service.CatalogService;
import dev.willcall.catalog.store.SeatRepository;
import dev.willcall.payment.FakePaymentGateway;
import dev.willcall.payment.PaymentBehavior;
import dev.willcall.platform.admin.InvariantController;
import dev.willcall.platform.web.ApiException;
import dev.willcall.reservation.domain.HoldGroup;
import dev.willcall.reservation.service.CheckoutService;
import dev.willcall.reservation.service.HoldExpiryScheduler;
import dev.willcall.reservation.service.ReservationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs random sequences of hold, retry, confirm, cancel and expire against the real service and a
 * hand-written reference model, and fails on the first disagreement.
 *
 * <p>Example-based tests check the cases somebody thought of. This checks the ones nobody did: a
 * cancel arriving after an expire that arrived after a confirm on a group whose seats overlap
 * another group's. Those orderings are where a reservation system actually breaks.
 *
 * <p>Both sides are driven by the same command list. The model is plain maps over seat indices and
 * was written without reference to the service's structure, so agreement between the two is
 * evidence rather than tautology.
 *
 * <p><b>Scale.</b> 1,000 sequences by default, 10,000 with {@code -Dwillcall.longMode=true}. Each
 * sequence is up to twelve commands against a six-seat event, which is small on purpose: bugs in
 * interleaving show up at tiny sizes, and a small event means a random command has a real chance of
 * colliding with an existing hold rather than finding empty space.
 */
@JqwikSpringSupport
@SpringBootTest(
    classes = WillcallApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReservationModelPropertyTest {

  private static final Logger log = LoggerFactory.getLogger(ReservationModelPropertyTest.class);

  private static final int SEAT_COUNT = 6;
  private static final int HOLD_TTL_SECONDS = 60;

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("willcall")
          .withUsername("willcall")
          .withPassword("willcall")
          .withCommand(
              "postgres",
              "-c",
              "fsync=off",
              "-c",
              "synchronous_commit=off",
              "-c",
              "full_page_writes=off")
          .withReuse(true);

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  static {
    POSTGRES.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "spring.data.redis.url",
        () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    registry.add("willcall.sweeper.enabled", () -> false);
    registry.add("willcall.outbox.enabled", () -> false);
    registry.add("willcall.holds.max-active-per-user", () -> 0);
    registry.add("willcall.payment.latency-ms", () -> 0);
    registry.add("willcall.payment.jitter-ms", () -> 0);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired CatalogService catalog;
  @Autowired ReservationService reservations;
  @Autowired CheckoutService checkout;
  @Autowired HoldExpiryScheduler sweeper;
  @Autowired SeatRepository seatRepository;
  @Autowired FakePaymentGateway gateway;

  private Event event;
  private List<UUID> seatIds;

  private static int tries() {
    return Boolean.getBoolean("willcall.longMode") ? 10_000 : 1_000;
  }

  /** The commands a sequence is built from. */
  sealed interface Command {
    record HoldExact(List<Integer> seatIndices) implements Command {}

    record HoldBestAvailable(int quantity) implements Command {}

    record Confirm(int groupSlot) implements Command {}

    record Cancel(int groupSlot) implements Command {}

    record RetryConfirm(int groupSlot) implements Command {}

    /** Advances time past every current hold and runs the sweeper. */
    record ExpireEverything() implements Command {}
  }

  @Provide
  Arbitrary<List<Command>> commandSequences() {
    Arbitrary<Command> holdExact =
        Arbitraries.integers()
            .between(0, SEAT_COUNT - 1)
            .set()
            .ofMinSize(1)
            .ofMaxSize(3)
            .map(set -> new Command.HoldExact(new ArrayList<>(set)));

    Arbitrary<Command> holdBest =
        Arbitraries.integers().between(1, 3).map(Command.HoldBestAvailable::new);

    Arbitrary<Command> confirm = Arbitraries.integers().between(0, 3).map(Command.Confirm::new);
    Arbitrary<Command> cancel = Arbitraries.integers().between(0, 3).map(Command.Cancel::new);
    Arbitrary<Command> retry = Arbitraries.integers().between(0, 3).map(Command.RetryConfirm::new);
    Arbitrary<Command> expire = Arbitraries.just(new Command.ExpireEverything());

    return Arbitraries.oneOf(
            List.of(
                holdExact.map(c -> c),
                holdBest.map(c -> c),
                confirm.map(c -> c),
                cancel.map(c -> c),
                retry.map(c -> c),
                expire.map(c -> c)))
        .list()
        .ofMinSize(1)
        .ofMaxSize(12);
  }

  @BeforeTry
  void freshEvent() {
    gateway.reset();
    jdbc.execute(
        """
        truncate table outbox, idempotency_records, order_lines, orders,
                       holds, hold_groups, seats, seat_rows, sections,
                       price_tiers, events, venues
        restart identity cascade
        """);
    event =
        catalog.createEvent(
            new CatalogService.CreateEventSpec(
                "Model Venue",
                "Model Event",
                Instant.now().plusSeconds(86_400),
                Instant.now().minusSeconds(60),
                HOLD_TTL_SECONDS,
                SEAT_COUNT,
                EventStatus.ON_SALE,
                List.of(new CatalogService.PriceTierSpec("Standard", 1_000, "USD")),
                List.of(new CatalogService.SectionSpec("Floor", 1, SEAT_COUNT, "Standard"))));
    seatIds =
        seatRepository.findByEvent(event.id()).stream()
            .sorted(java.util.Comparator.comparing(s -> s.id().toString()))
            .map(Seat::id)
            .toList();
  }

  @Property
  void theServiceAgreesWithTheReferenceModel(@ForAll("commandSequences") List<Command> commands) {
    ReferenceModel model = new ReferenceModel(SEAT_COUNT);
    // Slot -> (real group id, model group id). Slots let a generated command refer to "the
    // second group created so far" without the generator needing to know what ids exist.
    Map<Integer, String> modelGroupBySlot = new LinkedHashMap<>();
    Map<Integer, UUID> realGroupBySlot = new LinkedHashMap<>();
    int slot = 0;

    for (Command command : commands) {
      switch (command) {
        case Command.HoldExact hold -> {
          String modelGroupId = "g" + slot;
          List<UUID> wanted = hold.seatIndices().stream().map(seatIds::get).toList();
          boolean modelGranted =
              model.tryHoldExact(modelGroupId, "buyer-0001", hold.seatIndices(), HOLD_TTL_SECONDS);

          UUID realGroupId = null;
          boolean realGranted = true;
          try {
            HoldGroup group =
                reservations.acquire(
                    event.id(), "buyer-0001", AllocationRequest.exact(event.id(), wanted));
            realGroupId = group.id();
          } catch (ApiException e) {
            realGranted = false;
          }

          assertThat(realGranted)
              .as("hold of %s after %s", hold.seatIndices(), describe(commands))
              .isEqualTo(modelGranted);

          if (modelGranted) {
            modelGroupBySlot.put(slot, modelGroupId);
            realGroupBySlot.put(slot, realGroupId);
            slot++;
          }
        }

        case Command.HoldBestAvailable hold -> {
          String modelGroupId = "g" + slot;
          var modelSeats =
              model.tryHoldBestAvailable(
                  modelGroupId, "buyer-0001", hold.quantity(), HOLD_TTL_SECONDS);

          UUID realGroupId = null;
          List<UUID> realSeats = List.of();
          try {
            HoldGroup group =
                reservations.acquire(
                    event.id(),
                    "buyer-0001",
                    AllocationRequest.bestAvailable(event.id(), hold.quantity()));
            realGroupId = group.id();
            realSeats = group.seatIds();
          } catch (ApiException e) {
            // stays empty
          }

          assertThat(realGroupId != null)
              .as("best-available(%s) after %s", hold.quantity(), describe(commands))
              .isEqualTo(modelSeats.isPresent());

          if (modelSeats.isPresent()) {
            // Both sides take the lowest-ordered free seats, so the actual seats must match too,
            // not merely the count.
            List<UUID> expected = modelSeats.get().stream().map(seatIds::get).sorted().toList();
            assertThat(realSeats.stream().sorted().toList())
                .as("which seats best-available chose after %s", describe(commands))
                .isEqualTo(expected);
            modelGroupBySlot.put(slot, modelGroupId);
            realGroupBySlot.put(slot, realGroupId);
            slot++;
          }
        }

        case Command.Confirm confirm -> {
          Integer target = resolveSlot(confirm.groupSlot(), realGroupBySlot);
          if (target == null) break;
          model.extend(modelGroupBySlot.get(target), HOLD_TTL_SECONDS);
          boolean modelConfirmed = model.confirm(modelGroupBySlot.get(target));
          boolean realConfirmed = tryConfirm(realGroupBySlot.get(target));
          assertThat(realConfirmed)
              .as("confirm of slot %s after %s", target, describe(commands))
              .isEqualTo(modelConfirmed);
        }

        case Command.RetryConfirm retry -> {
          Integer target = resolveSlot(retry.groupSlot(), realGroupBySlot);
          if (target == null) break;
          // A retry of a confirm that already happened must change nothing on either side.
          Map<ReferenceModel.SeatState, Integer> before = model.counts();
          model.extend(modelGroupBySlot.get(target), HOLD_TTL_SECONDS);
          boolean modelConfirmed = model.confirm(modelGroupBySlot.get(target));
          boolean realConfirmed = tryConfirm(realGroupBySlot.get(target));
          assertThat(realConfirmed)
              .as("retry confirm of slot %s after %s", target, describe(commands))
              .isEqualTo(modelConfirmed);
          if (!modelConfirmed) {
            assertThat(model.counts()).isEqualTo(before);
          }
        }

        case Command.Cancel cancel -> {
          Integer target = resolveSlot(cancel.groupSlot(), realGroupBySlot);
          if (target == null) break;
          boolean modelCancelled = model.cancel(modelGroupBySlot.get(target));
          boolean realCancelled = tryCancel(realGroupBySlot.get(target));
          assertThat(realCancelled)
              .as("cancel of slot %s after %s", target, describe(commands))
              .isEqualTo(modelCancelled);
        }

        case Command.ExpireEverything ignored -> {
          model.advance(HOLD_TTL_SECONDS * 10L);
          model.sweep();
          jdbc.update(
              "update holds set expires_at = now() - interval '1 second' where status = 'ACTIVE'");
          jdbc.update(
              "update hold_groups set expires_at = now() - interval '1 second' where status = 'ACTIVE'");
          sweeper.drain();
        }
      }

      assertSeatsMatch(model, commands);
    }

    assertInvariants(commands);
  }

  private Integer resolveSlot(int requested, Map<Integer, UUID> realGroups) {
    if (realGroups.isEmpty()) return null;
    List<Integer> slots = new ArrayList<>(realGroups.keySet());
    return slots.get(requested % slots.size());
  }

  private boolean tryConfirm(UUID groupId) {
    try {
      checkout.checkout(groupId, "buyer-0001", PaymentBehavior.SUCCEED);
      return true;
    } catch (ApiException e) {
      return false;
    }
  }

  private boolean tryCancel(UUID groupId) {
    try {
      reservations.cancel(groupId, "buyer-0001");
      return true;
    } catch (ApiException e) {
      return false;
    }
  }

  private void assertSeatsMatch(ReferenceModel model, List<Command> commands) {
    List<Seat> actual =
        seatRepository.findByEvent(event.id()).stream()
            .sorted(java.util.Comparator.comparing(s -> s.id().toString()))
            .toList();

    for (int i = 0; i < SEAT_COUNT; i++) {
      ReferenceModel.SeatState expected = model.seat(i);
      String got = actual.get(i).status().name();
      String want = expected.name();
      assertThat(got).as("seat %s after %s", i, describe(commands)).isEqualTo(want);
    }
  }

  private void assertInvariants(List<Command> commands) {
    for (Map.Entry<String, String> check : InvariantController.CHECKS.entrySet()) {
      List<String> offending = jdbc.queryForList(check.getValue(), String.class);
      assertThat(offending)
          .as("invariant %s after %s", check.getKey(), describe(commands))
          .isEmpty();
    }
  }

  private static String describe(List<Command> commands) {
    return commands.toString();
  }

  /** Records the configured scale so a run's log says how many sequences it actually did. */
  @net.jqwik.api.lifecycle.AfterProperty
  void reportScale() {
    log.info(
        "model-based suite ran with jqwik.tries.default={} (long mode target {})",
        System.getProperty("jqwik.tries.default", "unset"),
        tries());
  }
}
