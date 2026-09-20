package dev.willcall.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The token is the only thing standing between the queue and the seats, so what it refuses matters
 * more than what it accepts.
 */
class AdmissionTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

  private AdmissionTokenService service(String key, Duration validity, Instant now) {
    return new AdmissionTokenService(key, validity, Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("a token verifies for the buyer and event it was issued to")
  void roundTrip() {
    AdmissionTokenService tokens = service("k", Duration.ofMinutes(10), NOW);
    String eventId = UUID.randomUUID().toString();

    String token = tokens.issue(eventId, "buyer-00000001");

    assertThat(tokens.verify(token, eventId, "buyer-00000001")).isPresent();
  }

  @Test
  @DisplayName("a token for another event does not open this one")
  void notTransferableBetweenEvents() {
    AdmissionTokenService tokens = service("k", Duration.ofMinutes(10), NOW);
    String token = tokens.issue(UUID.randomUUID().toString(), "buyer-00000001");

    assertThat(tokens.verify(token, UUID.randomUUID().toString(), "buyer-00000001")).isEmpty();
  }

  @Test
  @DisplayName("a token for another buyer does not admit this one")
  void notTransferableBetweenBuyers() {
    AdmissionTokenService tokens = service("k", Duration.ofMinutes(10), NOW);
    String eventId = UUID.randomUUID().toString();
    String token = tokens.issue(eventId, "buyer-00000001");

    assertThat(tokens.verify(token, eventId, "buyer-00000002")).isEmpty();
  }

  @Test
  @DisplayName("an expired token is refused")
  void expiryIsEnforced() {
    String eventId = UUID.randomUUID().toString();
    AdmissionTokenService issuer = service("k", Duration.ofMinutes(10), NOW);
    String token = issuer.issue(eventId, "buyer-00000001");

    AdmissionTokenService later =
        service("k", Duration.ofMinutes(10), NOW.plus(Duration.ofMinutes(11)));

    assertThat(later.verify(token, eventId, "buyer-00000001")).isEmpty();
  }

  @Test
  @DisplayName("a token signed with a different key is refused")
  void signatureIsChecked() {
    String eventId = UUID.randomUUID().toString();
    String token = service("one-key", Duration.ofMinutes(10), NOW).issue(eventId, "buyer-00000001");

    assertThat(
            service("another-key", Duration.ofMinutes(10), NOW)
                .verify(token, eventId, "buyer-00000001"))
        .isEmpty();
  }

  @Test
  @DisplayName("garbage is refused rather than throwing")
  void garbageIsRefused() {
    AdmissionTokenService tokens = service("k", Duration.ofMinutes(10), NOW);
    String eventId = UUID.randomUUID().toString();

    assertThat(tokens.verify(null, eventId, "buyer-00000001")).isEmpty();
    assertThat(tokens.verify("", eventId, "buyer-00000001")).isEmpty();
    assertThat(tokens.verify("not-a-token", eventId, "buyer-00000001")).isEmpty();
    assertThat(tokens.verify("a.b.c", eventId, "buyer-00000001")).isEmpty();
  }

  @Test
  @DisplayName("all replicas sharing a key can verify each other's tokens")
  void sameKeyDifferentInstance() {
    // A buyer admitted by one replica must not be refused by the next one the load balancer picks.
    String eventId = UUID.randomUUID().toString();
    String token = service("shared", Duration.ofMinutes(10), NOW).issue(eventId, "buyer-00000001");

    assertThat(
            service("shared", Duration.ofMinutes(10), NOW).verify(token, eventId, "buyer-00000001"))
        .isPresent();
  }

  @Test
  @DisplayName("the token reports when it expires, so a client can refresh before it does")
  void expiryIsVisible() {
    AdmissionTokenService tokens = service("k", Duration.ofMinutes(10), NOW);
    String eventId = UUID.randomUUID().toString();

    AdmissionToken token =
        tokens
            .verify(tokens.issue(eventId, "buyer-00000001"), eventId, "buyer-00000001")
            .orElseThrow();

    assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    assertThat(token.isExpiredAt(NOW)).isFalse();
    assertThat(token.isExpiredAt(NOW.plus(Duration.ofMinutes(11)))).isTrue();
  }
}
