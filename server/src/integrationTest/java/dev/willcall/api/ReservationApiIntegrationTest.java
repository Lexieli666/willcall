package dev.willcall.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.support.HttpIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The wire contract.
 *
 * <p>Status codes here are a client-facing API, not an implementation detail. The distinctions that
 * matter, and that each test pins:
 *
 * <ul>
 *   <li>409 — a legitimate answer; ask for something else, do not retry this.
 *   <li>410 — the hold is gone; start again.
 *   <li>422 — your request contradicts itself; retrying will not help.
 *   <li>400 — malformed; fix the client.
 *   <li>402/504 — the payment failed or did not answer, and those mean different things for the
 *       seats.
 * </ul>
 *
 * <p>{@code @AutoConfigureObservability} is present because Spring Boot switches metrics export off
 * in tests by default. Without it the Prometheus endpoint is simply not mapped, and a test that
 * asserted the dashboards' counters exist would fail for a reason that has nothing to do with the
 * application.
 */
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class ReservationApiIntegrationTest extends HttpIntegrationTestBase {

  private String eventId;

  @BeforeEach
  void setUp() {
    eventId = createEvent(4, 10, 60, 6);
  }

  @Test
  @DisplayName("creating an event returns 201 and the capacity it generated")
  void createEventReturnsCapacity() {
    ResponseEntity<String> response = get("/api/events/" + eventId, "buyer-00000001");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).contains("\"capacity\":40");
  }

  @Test
  @DisplayName("the seat map comes back grouped by section and row, with prices")
  void seatMapIsNested() {
    ResponseEntity<String> response = get("/api/events/" + eventId + "/seats", "buyer-00000001");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    String body = response.getBody();
    assertThat(body).contains("\"sections\"").contains("\"rows\"").contains("\"seats\"");
    assertThat(body).contains("\"priceCents\":2500");
    assertThat(field(body, "availableCount")).isEqualTo("40");
  }

  @Test
  @DisplayName("a hold returns 201 with the seats and a server-computed countdown")
  void holdReturns201() {
    ResponseEntity<String> response =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":2}", "buyer-00000001", "k1");

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(field(response.getBody(), "status")).isEqualTo("ACTIVE");
    assertThat(Integer.parseInt(field(response.getBody(), "secondsRemaining"))).isBetween(1, 60);
  }

  @Test
  @DisplayName("a request with no buyer identity is 400, not an anonymous hold")
  void missingIdentityIs400() {
    ResponseEntity<String> response =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", null, null);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(field(response.getBody(), "code")).isEqualTo("missing_user_ref");
  }

  @Test
  @DisplayName("a buyer reference that is too short is rejected rather than stored")
  void malformedIdentityIs400() {
    ResponseEntity<String> response =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "ab", null);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("sending both seatIds and quantity is 400, because the two mean different things")
  void bothSeatIdsAndQuantityIs400() {
    ResponseEntity<String> response =
        post(
            "/api/events/" + eventId + "/holds",
            "{\"quantity\":2,\"seatIds\":[\"00000000-0000-0000-0000-000000000001\"]}",
            "buyer-00000001",
            null);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(field(response.getBody(), "code")).isEqualTo("invalid_request");
  }

  @Test
  @DisplayName("asking for more than the event's per-order cap is 409 with a code a client can use")
  void overCapIs409() {
    ResponseEntity<String> response =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":7}", "buyer-00000001", null);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(field(response.getBody(), "code")).isEqualTo("too_many_seats");
    assertThat(response.getHeaders().getContentType().toString())
        .startsWith("application/problem+json");
  }

  @Test
  @DisplayName("asking for a quantity outside the schema is 400, not 409")
  void outOfSchemaQuantityIs400() {
    ResponseEntity<String> response =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":9999}", "buyer-00000001", null);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("a sold-out event answers 409 sold_out, not 404 and not 500")
  void soldOutIs409() {
    // Forty seats, eight buyers, five each: the event is exactly sold out and the ninth buyer
    // must be told so rather than getting a partial allocation.
    for (int i = 0; i < 8; i++) {
      ResponseEntity<String> held =
          post(
              "/api/events/" + eventId + "/holds",
              "{\"quantity\":5}",
              "buyer-%08d".formatted(i),
              null);
      assertThat(held.getStatusCode().value()).as("buyer %s: %s", i, held.getBody()).isEqualTo(201);
    }

    ResponseEntity<String> response =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-99999999", null);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(field(response.getBody(), "code")).isIn("sold_out", "seat_unavailable");
  }

  @Test
  @DisplayName("a replayed confirm carries Idempotency-Replayed and the original body")
  void replayIsMarked() {
    String holdId =
        field(
            post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-00000001", "h1")
                .getBody(),
            "holdId");

    ResponseEntity<String> first =
        post("/api/orders", "{\"holdId\":\"" + holdId + "\"}", "buyer-00000001", "o1");
    ResponseEntity<String> second =
        post("/api/orders", "{\"holdId\":\"" + holdId + "\"}", "buyer-00000001", "o1");

    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isNull();

    assertThat(second.getStatusCode().value()).isEqualTo(201);
    assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(field(second.getBody(), "orderId")).isEqualTo(field(first.getBody(), "orderId"));
  }

  @Test
  @DisplayName("reusing a key with a different body is 422 and names the key")
  void keyReuseIs422() {
    String holdA =
        field(
            post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-00000001", "h1")
                .getBody(),
            "holdId");
    String holdB =
        field(
            post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-00000001", "h2")
                .getBody(),
            "holdId");

    post("/api/orders", "{\"holdId\":\"" + holdA + "\"}", "buyer-00000001", "same-key");
    ResponseEntity<String> clash =
        post("/api/orders", "{\"holdId\":\"" + holdB + "\"}", "buyer-00000001", "same-key");

    assertThat(clash.getStatusCode().value()).isEqualTo(422);
    assertThat(field(clash.getBody(), "code")).isEqualTo("idempotency_key_reused");
    assertThat(field(clash.getBody(), "idempotencyKey")).isEqualTo("same-key");
  }

  @Test
  @DisplayName("a declined payment is 402 and the seats are already back on sale")
  void declineIs402() {
    String holdId =
        field(
            post("/api/events/" + eventId + "/holds", "{\"quantity\":2}", "buyer-00000001", "h1")
                .getBody(),
            "holdId");

    ResponseEntity<String> response =
        post(
            "/api/orders",
            "{\"holdId\":\"" + holdId + "\",\"paymentBehavior\":\"DECLINE\"}",
            "buyer-00000001",
            "o1");

    assertThat(response.getStatusCode().value()).isEqualTo(402);
    assertThat(field(response.getBody(), "code")).isEqualTo("payment_declined");
    assertThat(
            field(
                get("/api/events/" + eventId + "/seats", "buyer-00000001").getBody(),
                "availableCount"))
        .isEqualTo("40");
  }

  @Test
  @DisplayName("a gateway timeout is 504 with Retry-After, and the seats stay held")
  void timeoutIs504WithRetryAfter() {
    String holdId =
        field(
            post("/api/events/" + eventId + "/holds", "{\"quantity\":2}", "buyer-00000001", "h1")
                .getBody(),
            "holdId");

    ResponseEntity<String> response =
        post(
            "/api/orders",
            "{\"holdId\":\"" + holdId + "\",\"paymentBehavior\":\"TIMEOUT\"}",
            "buyer-00000001",
            "o1");

    assertThat(response.getStatusCode().value()).isEqualTo(504);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
    assertThat(
            field(
                get("/api/events/" + eventId + "/seats", "buyer-00000001").getBody(), "heldCount"))
        .isEqualTo("2");
  }

  @Test
  @DisplayName("confirming an expired hold is 410, not 404 and not 409")
  void expiredHoldIs410() {
    String holdId =
        field(
            post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-00000001", "h1")
                .getBody(),
            "holdId");

    jdbc.update("update holds set expires_at = now() - interval '1 second'");
    jdbc.update("update hold_groups set expires_at = now() - interval '1 second'");

    ResponseEntity<String> response =
        post("/api/orders", "{\"holdId\":\"" + holdId + "\"}", "buyer-00000001", "o1");

    assertThat(response.getStatusCode().value()).isEqualTo(410);
    assertThat(field(response.getBody(), "code")).isEqualTo("hold_expired");
  }

  @Test
  @DisplayName("cancelling returns 204 and the seats immediately")
  void cancelIs204() {
    String holdId =
        field(
            post("/api/events/" + eventId + "/holds", "{\"quantity\":3}", "buyer-00000001", "h1")
                .getBody(),
            "holdId");

    ResponseEntity<String> response = delete("/api/holds/" + holdId, "buyer-00000001");

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(
            field(
                get("/api/events/" + eventId + "/seats", "buyer-00000001").getBody(),
                "availableCount"))
        .isEqualTo("40");
  }

  @Test
  @DisplayName("another buyer's hold is 404, so the endpoint does not confirm it exists")
  void someoneElsesHoldIs404() {
    String holdId =
        field(
            post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-00000001", "h1")
                .getBody(),
            "holdId");

    ResponseEntity<String> response = delete("/api/holds/" + holdId, "buyer-00000002");

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  @DisplayName("an unknown event is 404 with a problem body, not a stack trace")
  void unknownEventIs404() {
    ResponseEntity<String> response =
        get("/api/events/00000000-0000-0000-0000-000000000000/seats", "buyer-00000001");

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(field(response.getBody(), "code")).isEqualTo("event_not_found");
    assertThat(response.getBody()).doesNotContain("Exception");
  }

  @Test
  @DisplayName("my-state returns holds, orders and the server's clock in one call")
  void buyerStateIsOneCall() {
    post("/api/events/" + eventId + "/holds", "{\"quantity\":2}", "buyer-00000001", "h1");

    ResponseEntity<String> response = get("/api/events/" + eventId + "/me", "buyer-00000001");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody())
        .contains("\"serverTime\"")
        .contains("\"holds\"")
        .contains("\"orders\"");
    assertThat(field(response.getBody(), "availableSeats")).isEqualTo("38");
  }

  @Test
  @DisplayName("a paused event refuses holds with 409, and resuming lets them through again")
  void pausedEventRefusesHolds() {
    post("/api/events/" + eventId + "/status/PAUSED", "", "buyer-organizer", null);

    ResponseEntity<String> paused =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-00000001", null);
    assertThat(paused.getStatusCode().value()).isEqualTo(409);
    assertThat(field(paused.getBody(), "code")).isEqualTo("event_not_on_sale");

    post("/api/events/" + eventId + "/status/ON_SALE", "", "buyer-organizer", null);
    ResponseEntity<String> resumed =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-00000001", null);
    assertThat(resumed.getStatusCode().value()).isEqualTo(201);
  }

  @Test
  @DisplayName("the admin invariant endpoint reports every check by name")
  void adminInvariantEndpoint() {
    post("/api/events/" + eventId + "/holds", "{\"quantity\":2}", "buyer-00000001", "h1");

    ResponseEntity<String> response =
        post("/api/admin/verify-invariants", "", "buyer-organizer", null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(field(response.getBody(), "passed")).isEqualTo("true");
    assertThat(response.getBody()).contains("one_active_hold_per_seat");
  }

  @Test
  @DisplayName("health and readiness answer without an identity, because probes have none")
  void probesNeedNoIdentity() {
    assertThat(get("/health", null).getStatusCode().value()).isEqualTo(200);
    assertThat(get("/ready", null).getStatusCode().value()).isEqualTo(200);
    assertThat(get("/api/system/status", null).getStatusCode().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("the metrics endpoint exposes the counters the dashboards read")
  void metricsAreExposed() {
    post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-00000001", "h1");

    ResponseEntity<String> actuatorIndex = get("/actuator", null);
    ResponseEntity<String> prometheus = get("/actuator/prometheus", null);

    assertThat(actuatorIndex.getStatusCode().value())
        .as("actuator index: %s", actuatorIndex.getBody())
        .isEqualTo(200);
    assertThat(prometheus.getStatusCode().value())
        .as("prometheus endpoint: %s", prometheus.getBody())
        .isEqualTo(200);
    assertThat(prometheus.getBody()).contains("willcall_holds_granted_total");
    assertThat(prometheus.getBody()).contains("http_server_requests_seconds");
  }
}
