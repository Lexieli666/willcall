package dev.willcall.support;

import dev.willcall.WillcallApplication;
import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.domain.EventStatus;
import dev.willcall.catalog.service.CatalogService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and real Redis for every integration test.
 *
 * <p>The containers are static and reused across classes. Starting a fresh PostgreSQL per test
 * class would add roughly a second per class and, more importantly, would tempt someone to reduce
 * the number of integration tests to keep the build fast. Isolation comes from truncating between
 * tests instead, which is both faster and a better model of production, where the schema is
 * long-lived and only the data changes.
 *
 * <p>The connection pool is deliberately larger here than the production default: the concurrency
 * suite fires ten thousand simultaneous acquisitions and a twenty-connection pool would turn that
 * into a test of the pool rather than of the locking.
 */
@SpringBootTest(
    classes = WillcallApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class IntegrationTestBase {

  /** See {@link #REDIS}. One above the compose stack's port, so the two never collide. */
  protected static final int REDIS_HOST_PORT = 16380;

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("willcall")
          .withUsername("willcall")
          .withPassword("willcall")
          .withCommand(
              "postgres",
              "-c",
              "max_connections=300",
              "-c",
              "fsync=off",
              "-c",
              "synchronous_commit=off",
              "-c",
              "full_page_writes=off")
          .withReuse(true);

  /**
   * Redis on a fixed host port.
   *
   * <p>Testcontainers normally maps an ephemeral host port, which is right for isolation and wrong
   * here: {@code RedisOutageInvariantIntegrationTest} stops and restarts this container, and Docker
   * assigns a <em>new</em> host port on restart. The application kept dialling the old one and
   * Redis appeared never to come back, which cost a test run to diagnose. A fixed binding makes a
   * restart transparent to the application, which is also what a restart looks like in production,
   * where the address does not move.
   *
   * <p>16380 is one above the compose stack's 16379, so a local stack and a test run cannot
   * collide.
   */
  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  static {
    REDIS.setPortBindings(java.util.List.of(REDIS_HOST_PORT + ":6379"));
    // Started once for the whole JVM. Testcontainers' Ryuk reaps them when the JVM exits, and
    // withReuse keeps them alive between runs when reuse is enabled in
    // ~/.testcontainers.properties.
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

    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 64);
    registry.add("spring.datasource.hikari.connection-timeout", () -> 60_000);

    // Background loops are driven explicitly from the tests that care about them, so a test
    // never has to guess whether a sweep has run yet.
    registry.add("willcall.sweeper.enabled", () -> false);
    registry.add("willcall.outbox.enabled", () -> false);

    // Fast gateway by default; the tests that care about latency set their own behaviour.
    registry.add("willcall.payment.latency-ms", () -> 0);
    registry.add("willcall.payment.jitter-ms", () -> 0);
    registry.add("willcall.payment.timeout-ms", () -> 50);

    // No per-buyer cap by default: most tests drive many holds as one synthetic buyer, and the
    // cap has its own test.
    registry.add("willcall.holds.max-active-per-user", () -> 0);
  }

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected CatalogService catalog;
  @Autowired protected RedisConnectionFactory redis;

  @BeforeEach
  protected void resetEverything() {
    jdbc.execute(
        """
        truncate table outbox, idempotency_records, order_lines, orders,
                       admissions, holds, hold_groups, seats, seat_rows, sections,
                       price_tiers, events, venues
        restart identity cascade
        """);

    // Redis too, and it was not being reset for most of this project's life.
    //
    // The container is shared across test classes, like PostgreSQL, but only the tables were being
    // cleared. Rate-limit buckets survive with a ten-minute TTL, waiting-room sorted sets survive
    // indefinitely, and both are keyed by identifiers the tests reuse. The symptom was five
    // failures that appeared only in long mode and only after enough tests had run: a hold
    // answered 429 because an earlier test had spent that buyer's bucket, and an admission test
    // reading buyer-00000026 first because a previous test's queue was still there.
    //
    // Those tests were not passing because the code was right. They were passing because the suite
    // was short enough, which is the same kind of accident as a check that reports a pass over no
    // data.
    try (RedisConnection connection = redis.getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  /** Creates a single-section event with {@code rows x seatsPerRow} seats, on sale now. */
  protected Event createEvent(int rows, int seatsPerRow, int holdTtlSeconds) {
    return catalog.createEvent(
        new CatalogService.CreateEventSpec(
            "Test Venue",
            "Test Event",
            Instant.now().plusSeconds(86_400),
            Instant.now().minusSeconds(60),
            holdTtlSeconds,
            50,
            EventStatus.ON_SALE,
            List.of(new CatalogService.PriceTierSpec("Standard", 5_000, "USD")),
            List.of(new CatalogService.SectionSpec("Floor", rows, seatsPerRow, "Standard"))));
  }

  protected long countSeats(String status) {
    Long n = jdbc.queryForObject("select count(*) from seats where status = ?", Long.class, status);
    return n == null ? 0 : n;
  }
}
