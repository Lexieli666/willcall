package dev.willcall.support;

import dev.willcall.WillcallApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Drives the service over real HTTP.
 *
 * <p>The service-layer tests prove the reservation logic is right. These prove the wire contract is
 * right, which is a different claim: status codes, {@code Retry-After}, problem+json bodies, the
 * {@code Idempotency-Replayed} header, and what happens when a client sends no identity. Getting
 * the logic right and the status codes wrong produces a client that retries a 409 forever.
 */
@SpringBootTest(
    classes = WillcallApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class HttpIntegrationTestBase {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("willcall")
          .withUsername("willcall")
          .withPassword("willcall")
          .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off")
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
    TestBackgroundJobs.disable(registry);
    registry.add("willcall.holds.max-active-per-user", () -> 0);
    // The stream tests need the gap-injection and drain hooks; the deployed stack never sets this.
    registry.add("willcall.admin.test-hooks-enabled", () -> true);
    registry.add("willcall.payment.latency-ms", () -> 0);
    registry.add("willcall.payment.jitter-ms", () -> 0);
    registry.add("willcall.payment.timeout-ms", () -> 20);
  }

  @LocalServerPort protected int port;
  @Autowired protected TestRestTemplate rest;
  @Autowired protected JdbcTemplate jdbc;

  @Autowired protected RedisConnectionFactory redis;

  @BeforeEach
  void resetEverything() {
    TestStateReset.clean(jdbc, redis);
  }

  protected HttpHeaders headers(String buyer, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (buyer != null) headers.set("X-Willcall-User", buyer);
    if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
    return headers;
  }

  protected ResponseEntity<String> post(String path, String body, String buyer, String key) {
    return rest.exchange(
        path, HttpMethod.POST, new HttpEntity<>(body, headers(buyer, key)), String.class);
  }

  protected ResponseEntity<String> get(String path, String buyer) {
    return rest.exchange(
        path, HttpMethod.GET, new HttpEntity<>(headers(buyer, null)), String.class);
  }

  protected ResponseEntity<String> delete(String path, String buyer) {
    return rest.exchange(
        path, HttpMethod.DELETE, new HttpEntity<>(headers(buyer, null)), String.class);
  }

  /** Reads one field out of a JSON response without pulling in a JSON path library. */
  protected static String field(String json, String name) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path(name).asText();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("not JSON: " + json, e);
    }
  }

  protected String createEvent(int rows, int seatsPerRow, int holdTtlSeconds, int maxPerOrder) {
    String body =
        """
        {
          "venueName": "HTTP Arena",
          "eventName": "HTTP Event",
          "holdTtlSeconds": %d,
          "maxSeatsPerOrder": %d,
          "status": "ON_SALE",
          "priceTiers": [{"name": "Standard", "amountCents": 2500, "currency": "USD"}],
          "sections": [{"name": "Floor", "rowCount": %d, "seatsPerRow": %d, "priceTierName": "Standard"}]
        }
        """
            .formatted(holdTtlSeconds, maxPerOrder, rows, seatsPerRow);
    ResponseEntity<String> response = post("/api/events", body, "buyer-organizer", null);
    if (response.getStatusCode().value() != 201) {
      throw new IllegalStateException("could not create event: " + response.getBody());
    }
    return field(response.getBody(), "id");
  }
}
