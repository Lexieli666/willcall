package dev.willcall.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.support.HttpIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The waiting room's wire contract, and the admission check in front of holds.
 *
 * <p>The claim being tested is that an event with a queue cannot be bought from without going
 * through it. A queue that can be walked around is decoration.
 */
@TestPropertySource(
    properties = {
      "willcall.waitingroom.admitter-enabled=false",
      "willcall.waitingroom.broadcast-enabled=false",
      "willcall.ratelimit.enabled=false"
    })
class WaitingRoomApiIntegrationTest extends HttpIntegrationTestBase {

  @Autowired AdmissionScheduler admitter;
  @Autowired WaitingRoomService waitingRoom;

  private String eventId;

  @BeforeEach
  void setUp() {
    eventId = createEvent(10, 20, 120, 4);
    ResponseEntity<String> enabled =
        post(
            "/api/events/" + eventId + "/waiting-room?enabled=true&ratePerSecond=5",
            "",
            "buyer-organizer",
            null);
    assertThat(enabled.getStatusCode().value()).isEqualTo(204);
  }

  private ResponseEntity<String> holdWithToken(String buyer, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Willcall-User", buyer);
    headers.set("Idempotency-Key", "k-" + buyer + "-" + System.nanoTime());
    if (token != null) headers.set("X-Willcall-Admission", token);
    return rest.exchange(
        "/api/events/" + eventId + "/holds",
        HttpMethod.POST,
        new HttpEntity<>("{\"quantity\":1}", headers),
        String.class);
  }

  @Test
  @DisplayName("holding without being admitted is 403 and names the queue to join")
  void unadmittedBuyerIsRefused() {
    ResponseEntity<String> response = holdWithToken("buyer-00000001", null);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(field(response.getBody(), "code")).isEqualTo("not_admitted");
    // The client is told where to go, rather than being left to guess that a queue exists.
    assertThat(field(response.getBody(), "queueUrl")).contains("/queue/join");
  }

  @Test
  @DisplayName("a refused hold does not burn its idempotency key")
  void refusalDoesNotConsumeTheKey() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Willcall-User", "buyer-00000009");
    headers.set("Idempotency-Key", "reused-after-queueing");

    ResponseEntity<String> refused =
        rest.exchange(
            "/api/events/" + eventId + "/holds",
            HttpMethod.POST,
            new HttpEntity<>("{\"quantity\":1}", headers),
            String.class);
    assertThat(refused.getStatusCode().value()).isEqualTo(403);

    // Now queue, get admitted, and retry with the same key. It must run, not be replayed as the
    // 403 — which is why the admission check happens before the idempotency layer.
    post("/api/events/" + eventId + "/queue/join", "", "buyer-00000009", null);
    admitter.admitAll();
    String token =
        field(
            get("/api/events/" + eventId + "/queue/me", "buyer-00000009").getBody(),
            "admissionToken");
    headers.set("X-Willcall-Admission", token);

    ResponseEntity<String> retried =
        rest.exchange(
            "/api/events/" + eventId + "/holds",
            HttpMethod.POST,
            new HttpEntity<>("{\"quantity\":1}", headers),
            String.class);

    assertThat(retried.getStatusCode().value()).isEqualTo(201);
  }

  @Test
  @DisplayName("joining reports a position, and joining again reports the same one")
  void joinIsIdempotent() {
    ResponseEntity<String> first =
        post("/api/events/" + eventId + "/queue/join", "", "buyer-00000001", null);
    post("/api/events/" + eventId + "/queue/join", "", "buyer-00000002", null);
    ResponseEntity<String> again =
        post("/api/events/" + eventId + "/queue/join", "", "buyer-00000001", null);

    assertThat(first.getStatusCode().value()).isEqualTo(200);
    assertThat(field(first.getBody(), "state")).isEqualTo("WAITING");
    assertThat(field(first.getBody(), "position")).isEqualTo("1");
    assertThat(field(again.getBody(), "position")).isEqualTo("1");
    assertThat(field(again.getBody(), "queueLength")).isEqualTo("2");
  }

  @Test
  @DisplayName("an admitted buyer receives a token and can then hold")
  void admittedBuyerCanHold() {
    post("/api/events/" + eventId + "/queue/join", "", "buyer-00000003", null);
    admitter.admitAll();

    ResponseEntity<String> position = get("/api/events/" + eventId + "/queue/me", "buyer-00000003");
    assertThat(field(position.getBody(), "state")).isEqualTo("ADMITTED");
    String token = field(position.getBody(), "admissionToken");
    assertThat(token).isNotBlank();

    assertThat(holdWithToken("buyer-00000003", token).getStatusCode().value()).isEqualTo(201);
  }

  @Test
  @DisplayName("another buyer's token does not admit you")
  void tokenIsNotTransferable() {
    post("/api/events/" + eventId + "/queue/join", "", "buyer-00000004", null);
    admitter.admitAll();
    String token =
        field(
            get("/api/events/" + eventId + "/queue/me", "buyer-00000004").getBody(),
            "admissionToken");

    assertThat(holdWithToken("buyer-00000005", token).getStatusCode().value()).isEqualTo(403);
  }

  @Test
  @DisplayName("leaving gives up the place and is idempotent")
  void leaveIsIdempotent() {
    post("/api/events/" + eventId + "/queue/join", "", "buyer-00000006", null);
    post("/api/events/" + eventId + "/queue/join", "", "buyer-00000007", null);

    assertThat(
            delete("/api/events/" + eventId + "/queue/me", "buyer-00000006")
                .getStatusCode()
                .value())
        .isEqualTo(204);
    assertThat(
            delete("/api/events/" + eventId + "/queue/me", "buyer-00000006")
                .getStatusCode()
                .value())
        .isEqualTo(204);

    assertThat(
            field(
                get("/api/events/" + eventId + "/queue/me", "buyer-00000007").getBody(),
                "position"))
        .isEqualTo("1");
  }

  @Test
  @DisplayName("somebody queueing for seats that cannot exist is told so")
  void beyondInventoryIsSurfaced() {
    String tiny = createEvent(1, 2, 60, 2);
    post(
        "/api/events/" + tiny + "/waiting-room?enabled=true&ratePerSecond=1",
        "",
        "buyer-organizer",
        null);

    for (int i = 0; i < 5; i++) {
      post("/api/events/" + tiny + "/queue/join", "", "buyer-%08d".formatted(i), null);
    }

    assertThat(
            field(
                get("/api/events/" + tiny + "/queue/me", "buyer-00000000").getBody(),
                "beyondInventory"))
        .isEqualTo("false");
    assertThat(
            field(
                get("/api/events/" + tiny + "/queue/me", "buyer-00000004").getBody(),
                "beyondInventory"))
        .isEqualTo("true");
  }

  @Test
  @DisplayName("queue stats report depth, admitted count and the measured rate")
  void statsAreExposed() {
    post("/api/events/" + eventId + "/queue/join", "", "buyer-00000008", null);

    ResponseEntity<String> stats =
        get("/api/events/" + eventId + "/queue/stats", "buyer-organizer");

    assertThat(stats.getStatusCode().value()).isEqualTo(200);
    assertThat(field(stats.getBody(), "waiting")).isEqualTo("1");
    assertThat(Double.parseDouble(field(stats.getBody(), "admissionRatePerSecond"))).isEqualTo(5.0);
  }

  @Test
  @DisplayName("an event without a waiting room needs no token")
  void noQueueMeansNoToken() {
    String open = createEvent(2, 10, 60, 4);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Willcall-User", "buyer-00000010");
    headers.set("Idempotency-Key", "no-queue-1");

    ResponseEntity<String> response =
        rest.exchange(
            "/api/events/" + open + "/holds",
            HttpMethod.POST,
            new HttpEntity<>("{\"quantity\":1}", headers),
            String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
  }

  @Test
  @DisplayName("the queue reports its own server time, so a client is not trusting its own clock")
  void serverTimeIsReported() {
    ResponseEntity<String> response =
        post("/api/events/" + eventId + "/queue/join", "", "buyer-00000011", null);

    assertThat(field(response.getBody(), "serverTime")).isNotBlank();
  }
}
