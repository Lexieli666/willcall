package dev.willcall.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.support.HttpIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Rate limiting, over real HTTP.
 *
 * <p>The claim is not "a limiter exists" but "the limiter answers usefully". A bare 429 with no
 * {@code Retry-After} makes a client retry immediately, which converts rate limiting into a busy
 * loop and leaves the service worse off than with no limiter at all.
 */
@TestPropertySource(
    properties = {
      "willcall.ratelimit.holds-per-second=2",
      "willcall.ratelimit.holds-burst=3",
      "willcall.ratelimit.orders-per-second=1",
      "willcall.ratelimit.orders-burst=2"
    })
class RateLimitIntegrationTest extends HttpIntegrationTestBase {

  private String eventId;

  @BeforeEach
  void setUp() {
    eventId = createEvent(20, 20, 60, 4);
  }

  @Test
  @DisplayName("a burst past the limit gets 429 with a Retry-After a client can act on")
  void burstIsLimitedWithRetryAfter() {
    String buyer = "buyer-burst-0001";
    int limited = 0;
    String retryAfter = null;

    for (int i = 0; i < 12; i++) {
      ResponseEntity<String> response =
          post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", buyer, "k" + i);
      if (response.getStatusCode().value() == 429) {
        limited++;
        retryAfter = response.getHeaders().getFirst("Retry-After");
        assertThat(field(response.getBody(), "code")).isEqualTo("rate_limited");
      }
    }

    assertThat(limited).as("a burst of twelve past a burst limit of three must be refused").isPositive();
    assertThat(retryAfter).as("Retry-After must be present").isNotNull();
    assertThat(Integer.parseInt(retryAfter))
        .as("Retry-After: 0 invites an immediate retry, which is what the limiter is preventing")
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("the limit is per buyer, so one abuser does not refuse everybody else")
  void limitIsPerBuyer() {
    for (int i = 0; i < 10; i++) {
      post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-abusive-001", "a" + i);
    }

    // A different buyer, first request, must be served from a full bucket.
    ResponseEntity<String> innocent =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", "buyer-innocent-1", "i1");

    assertThat(innocent.getStatusCode().value()).isIn(201, 409);
  }

  @Test
  @DisplayName("the bucket refills, so a limited buyer is not locked out permanently")
  void bucketRefills() throws Exception {
    String buyer = "buyer-refill-001";
    for (int i = 0; i < 10; i++) {
      post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", buyer, "r" + i);
    }

    // Two per second, so a second and a half is comfortably enough for one token.
    Thread.sleep(1_500);

    ResponseEntity<String> after =
        post("/api/events/" + eventId + "/holds", "{\"quantity\":1}", buyer, "r-after");

    assertThat(after.getStatusCode().value()).isIn(201, 409);
  }

  @Test
  @DisplayName("reads are not limited, because refreshing a seat map is not the problem")
  void readsAreNotLimited() {
    String buyer = "buyer-reader-001";
    for (int i = 0; i < 30; i++) {
      ResponseEntity<String> response = get("/api/events/" + eventId + "/seats", buyer);
      assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
  }
}
