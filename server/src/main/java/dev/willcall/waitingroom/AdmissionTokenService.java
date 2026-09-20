package dev.willcall.waitingroom;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies admission tokens.
 *
 * <h2>Why signed rather than a lookup</h2>
 *
 * A token that is only an opaque id has to be checked against Redis on every hold request, which
 * puts Redis on the reservation path — the one thing {@code
 * docs/adr/0001-postgresql-owns-correctness-redis-only-accelerates.md} says must not happen. A
 * signed token is verified with a local HMAC, so admission survives Redis being down and the
 * reservation path stays independent of it.
 *
 * <p>The cost of that choice is honest and worth stating: a signed token cannot be revoked before
 * it expires. The expiry is therefore short, and the per-buyer hold cap is what limits the damage a
 * misbehaving admitted buyer can do.
 *
 * <h2>The signing key</h2>
 *
 * Read from configuration. In a deployment it comes from the secret store; the development default
 * exists so `docker compose up` works, and it is logged as a warning at start-up so nobody ships it
 * by accident. All replicas must share it, or a buyer admitted by one replica would be rejected by
 * the next.
 */
@Service
public class AdmissionTokenService {

  private static final Logger log = LoggerFactory.getLogger(AdmissionTokenService.class);
  private static final String DEVELOPMENT_SECRET = "willcall-development-signing-key-do-not-deploy";
  private static final String CLAIM_EVENT = "evt";
  private static final String ISSUER = "willcall";

  private final Algorithm algorithm;
  private final JWTVerifier verifier;
  private final Duration validity;
  private final Clock clock;

  public AdmissionTokenService(
      @Value("${willcall.waitingroom.signing-key:}") String signingKey,
      @Value("${willcall.waitingroom.token-validity:PT10M}") Duration validity,
      Clock clock) {
    String key = signingKey == null || signingKey.isBlank() ? DEVELOPMENT_SECRET : signingKey;
    if (DEVELOPMENT_SECRET.equals(key)) {
      log.warn(
          "waiting room is signing admission tokens with the built-in development key."
              + " Set willcall.waitingroom.signing-key before deploying this anywhere real.");
    }
    this.algorithm = Algorithm.HMAC256(key);
    // build(clock), not build(). The library's default verifier reads the system clock for the
    // expiry check, so this class took a Clock, used it to stamp `exp`, and then ignored it when
    // deciding whether `exp` had passed. In production both clocks are the same one and nothing is
    // wrong; in a test with a fixed clock the token is issued in the past and immediately refused,
    // so three tests here passed only when the suite happened to run within ten minutes of the
    // fixed instant. They were green this morning and red this evening for no reason anybody
    // changed. A seam that is honoured for half of an operation is worse than no seam.
    this.verifier =
        ((JWTVerifier.BaseVerification) JWT.require(algorithm).withIssuer(ISSUER)).build(clock);
    this.validity = validity;
    this.clock = clock;
  }

  public Duration validity() {
    return validity;
  }

  public String issue(String eventId, String userRef) {
    Instant now = clock.instant();
    return JWT.create()
        .withIssuer(ISSUER)
        .withSubject(userRef)
        .withClaim(CLAIM_EVENT, eventId)
        .withIssuedAt(now)
        .withExpiresAt(now.plus(validity))
        .sign(algorithm);
  }

  /**
   * Verifies a token for a specific event and buyer.
   *
   * <p>Both are checked against the token's own claims rather than trusted from the request: a
   * valid token for a different event, or for a different buyer, is not a valid token here. That is
   * the difference between "this string was signed by us" and "this buyer was admitted to this
   * event".
   */
  public Optional<AdmissionToken> verify(String token, String eventId, String userRef) {
    if (token == null || token.isBlank()) return Optional.empty();
    try {
      DecodedJWT decoded = verifier.verify(token);
      if (!eventId.equals(decoded.getClaim(CLAIM_EVENT).asString())) {
        log.debug("admission token is for a different event");
        return Optional.empty();
      }
      if (!userRef.equals(decoded.getSubject())) {
        log.debug("admission token is for a different buyer");
        return Optional.empty();
      }
      return Optional.of(
          new AdmissionToken(
              eventId, userRef, decoded.getIssuedAtAsInstant(), decoded.getExpiresAtAsInstant()));
    } catch (JWTVerificationException e) {
      // Includes expiry, a bad signature, and anything that is not a token at all. They are all
      // the same answer to the caller: you are not admitted.
      log.debug("admission token rejected: {}", e.getMessage());
      return Optional.empty();
    }
  }
}
