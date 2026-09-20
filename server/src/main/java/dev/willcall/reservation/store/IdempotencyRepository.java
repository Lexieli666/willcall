package dev.willcall.reservation.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** What a stored idempotency record holds. {@code responseBody} is the verbatim JSON reply. */
  public record StoredResponse(
      String userRef,
      String endpoint,
      String key,
      String fingerprint,
      String status,
      Integer responseStatus,
      String responseBody,
      UUID resourceId,
      Instant createdAt) {

    public boolean isCompleted() {
      return "COMPLETED".equals(status);
    }
  }

  /**
   * Claims the key, or reports that somebody already has it.
   *
   * <p>Uses {@code on conflict do nothing} rather than a select-then-insert: two concurrent retries
   * of the same request are exactly the case this table exists for, and a check-then-act here would
   * let both through.
   *
   * @return true if this caller now owns the key and should do the work
   */
  public boolean tryClaim(
      String userRef, String endpoint, String key, String fingerprint, Instant expiresAt) {
    try {
      int inserted =
          jdbc.update(
              """
              insert into idempotency_records
                (user_ref, endpoint, idempotency_key, request_fingerprint, status, expires_at)
              values (:userRef, :endpoint, :key, :fingerprint, 'IN_PROGRESS', :expiresAt)
              on conflict (user_ref, endpoint, idempotency_key) do nothing
              """,
              new MapSqlParameterSource()
                  .addValue("userRef", userRef)
                  .addValue("endpoint", endpoint)
                  .addValue("key", key)
                  .addValue("fingerprint", fingerprint)
                  .addValue("expiresAt", Timestamp.from(expiresAt)));
      return inserted == 1;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  public Optional<StoredResponse> find(String userRef, String endpoint, String key) {
    return jdbc
        .query(
            """
            select user_ref, endpoint, idempotency_key, request_fingerprint, status,
                   response_status, response_body::text as response_body, resource_id, created_at
            from idempotency_records
            where user_ref = :userRef and endpoint = :endpoint and idempotency_key = :key
            """,
            Map.of("userRef", userRef, "endpoint", endpoint, "key", key),
            (rs, i) ->
                new StoredResponse(
                    rs.getString("user_ref"),
                    rs.getString("endpoint"),
                    rs.getString("idempotency_key"),
                    rs.getString("request_fingerprint"),
                    rs.getString("status"),
                    rs.getObject("response_status", Integer.class),
                    rs.getString("response_body"),
                    rs.getObject("resource_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()))
        .stream()
        .findFirst();
  }

  public void complete(
      String userRef,
      String endpoint,
      String key,
      int responseStatus,
      String responseBody,
      UUID resourceId) {
    jdbc.update(
        """
        update idempotency_records
        set status = 'COMPLETED', response_status = :status, response_body = cast(:body as jsonb),
            resource_id = :resourceId, completed_at = now()
        where user_ref = :userRef and endpoint = :endpoint and idempotency_key = :key
        """,
        new MapSqlParameterSource()
            .addValue("userRef", userRef)
            .addValue("endpoint", endpoint)
            .addValue("key", key)
            .addValue("status", responseStatus)
            .addValue("body", responseBody)
            .addValue("resourceId", resourceId));
  }

  /**
   * Drops a claim whose work failed unexpectedly, so a retry is not locked out for the whole TTL by
   * a bug that has since been fixed or a pod that has since restarted.
   */
  public void release(String userRef, String endpoint, String key) {
    jdbc.update(
        """
        delete from idempotency_records
        where user_ref = :userRef and endpoint = :endpoint and idempotency_key = :key
          and status = 'IN_PROGRESS'
        """,
        Map.of("userRef", userRef, "endpoint", endpoint, "key", key));
  }

  public int deleteExpired(Instant now) {
    return jdbc.update(
        "delete from idempotency_records where expires_at <= :now",
        Map.of("now", Timestamp.from(now)));
  }
}
