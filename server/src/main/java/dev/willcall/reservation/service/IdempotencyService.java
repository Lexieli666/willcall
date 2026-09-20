package dev.willcall.reservation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import dev.willcall.reservation.store.IdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Makes a mutating endpoint safe to retry.
 *
 * <p>The contract, and why each part of it is there:
 *
 * <ol>
 *   <li><b>Same key, same request → the original answer, no second mutation.</b> This is the whole
 *       point. A client that times out and retries must not buy two seats.
 *   <li><b>Same key, different request → 422.</b> Returning the original answer would be worse than
 *       an error: the client would believe a request it never made had succeeded. The fingerprint
 *       is a hash of the method, path, buyer and canonical body.
 *   <li><b>Same key, still running → 409 with Retry-After.</b> Waiting for the first attempt inside
 *       the second request would tie up a thread and turn a retry storm into an outage.
 *   <li><b>The work threw → the claim is released.</b> Otherwise one bug would lock a buyer out of
 *       that key for the whole retention window.
 * </ol>
 *
 * <p>The claim is taken with {@code insert ... on conflict do nothing} rather than
 * select-then-insert, because two simultaneous retries are precisely the case this exists for and a
 * check-then-act would let both through.
 */
@Service
public class IdempotencyService {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

  private final IdempotencyRepository repository;
  private final ObjectMapper objectMapper;
  private final Duration retention;

  public IdempotencyService(
      IdempotencyRepository repository,
      ObjectMapper objectMapper,
      @Value("${willcall.idempotency.retention:PT24H}") Duration retention) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.retention = retention;
  }

  /** The stored outcome of a completed idempotent call. */
  public record Replay(int status, String body, UUID resourceId) {}

  /** What the caller's work produced, so it can be stored for a later replay. */
  public record Outcome<T>(int status, T body, UUID resourceId) {}

  /**
   * Runs {@code work} at most once for the given key.
   *
   * @param key the client's {@code Idempotency-Key}; when null, {@code work} runs unguarded
   * @return either the fresh result or the stored one from the first attempt
   */
  public <T> Result<T> execute(
      String key, String userRef, String endpoint, Object requestBody, Supplier<Outcome<T>> work) {

    if (key == null || key.isBlank()) {
      Outcome<T> outcome = work.get();
      return new Result<>(outcome.status(), outcome.body(), null, false);
    }

    String fingerprint = fingerprint(endpoint, userRef, requestBody);

    if (!repository.tryClaim(userRef, endpoint, key, fingerprint, Instant.now().plus(retention))) {
      return replayOrReject(key, userRef, endpoint, fingerprint);
    }

    try {
      Outcome<T> outcome = work.get();
      repository.complete(
          userRef,
          endpoint,
          key,
          outcome.status(),
          serialise(outcome.body()),
          outcome.resourceId());
      return new Result<>(outcome.status(), outcome.body(), null, false);
    } catch (ApiException e) {
      // A failure releases the claim so the retry actually runs again.
      //
      // The alternative — storing the failure and replaying it — looks tidier and is wrong. The
      // case idempotency exists for is a charge that succeeded behind a gateway timeout: the
      // client gets 504, retries with the same key, and that retry must reach the gateway, find
      // the recorded charge and confirm the order. Replaying the stored 504 would strand a buyer
      // who has already paid. A failed attempt mutated nothing that a rerun would duplicate, so
      // rerunning is safe as well as necessary.
      repository.release(userRef, endpoint, key);
      throw e;
    } catch (RuntimeException e) {
      repository.release(userRef, endpoint, key);
      log.warn("idempotent work failed, claim released for key={} endpoint={}", key, endpoint, e);
      throw e;
    }
  }

  private <T> Result<T> replayOrReject(
      String key, String userRef, String endpoint, String fingerprint) {
    Optional<IdempotencyRepository.StoredResponse> existing =
        repository.find(userRef, endpoint, key);
    if (existing.isEmpty()) {
      // The row vanished between the failed claim and this read: another attempt released it.
      throw new ApiException(
          ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS,
          "An identical request is being processed; retry shortly",
          java.util.Map.of(),
          1);
    }

    IdempotencyRepository.StoredResponse record = existing.get();
    if (!record.fingerprint().equals(fingerprint)) {
      throw new ApiException(
          ErrorCode.IDEMPOTENCY_KEY_REUSED,
          "That Idempotency-Key was used for a different request body",
          java.util.Map.of("idempotencyKey", key));
    }

    if (!record.isCompleted()) {
      throw new ApiException(
          ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS,
          "An identical request is still being processed; retry shortly",
          java.util.Map.of("idempotencyKey", key),
          1);
    }

    int status = record.responseStatus() == null ? 200 : record.responseStatus();
    return new Result<>(
        status, null, new Replay(status, record.responseBody(), record.resourceId()), true);
  }

  /**
   * A fingerprint over what the request actually asks for.
   *
   * <p>Jackson's tree model is used rather than the raw bytes so that whitespace and key order do
   * not make two identical requests look different, which would turn a legitimate retry into a 422.
   */
  public String fingerprint(String endpoint, String userRef, Object requestBody) {
    try {
      String canonical =
          requestBody == null
              ? ""
              : objectMapper.writeValueAsString(objectMapper.valueToTree(requestBody));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(endpoint.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(userRef.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("request body is not serialisable", e);
    }
  }

  private String serialise(Object body) {
    if (body == null) return null;
    try {
      return objectMapper.writeValueAsString(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("response body is not serialisable", e);
    }
  }

  /** Either a fresh result or a replay of the stored one. */
  public record Result<T>(int status, T body, Replay replay, boolean replayed) {}

  public int purgeExpired() {
    return repository.deleteExpired(Instant.now());
  }
}
