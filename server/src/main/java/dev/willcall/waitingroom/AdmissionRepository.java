package dev.willcall.waitingroom;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * The durable record of who was admitted, and in what order relative to when they arrived.
 *
 * <p>Redis holds the live queue and is allowed to disappear. This table is what the published
 * FIFO-inversion rate rests on: a claim about admission order computed from data that is deleted as
 * it is used would be unauditable.
 */
@Repository
public class AdmissionRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public AdmissionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record AdmissionRecord(
      UUID eventId, String userRef, Instant joinedAt, Instant admittedAt) {}

  /**
   * Records a batch of admissions.
   *
   * <p>{@code on conflict do nothing}: a buyer admitted twice — which the Redis script prevents,
   * but which a replay after a restart could still produce — must not become two rows and skew the
   * inversion rate.
   */
  public void recordAll(List<AdmissionRecord> records) {
    if (records.isEmpty()) return;
    SqlParameterSource[] batch =
        records.stream()
            .map(
                record ->
                    (SqlParameterSource)
                        new MapSqlParameterSource()
                            .addValue("eventId", record.eventId())
                            .addValue("userRef", record.userRef())
                            .addValue("joinedAt", Timestamp.from(record.joinedAt()))
                            .addValue("admittedAt", Timestamp.from(record.admittedAt())))
            .toArray(SqlParameterSource[]::new);

    jdbc.batchUpdate(
        """
        insert into admissions (event_id, user_ref, joined_at, admitted_at)
        values (:eventId, :userRef, :joinedAt, :admittedAt)
        on conflict (event_id, user_ref) do nothing
        """,
        batch);
  }

  /**
   * The FIFO-inversion rate: the share of admitted pairs served out of arrival order.
   *
   * <p>Computed in SQL rather than in Java so the calculation is auditable by anyone with a psql
   * prompt, and so it does not need every admission loaded into memory.
   *
   * <p>The measure is Kendall's tau distance, normalised: of all pairs of admitted buyers, how many
   * were admitted in the opposite order to their arrival. Strict FIFO is 0. Random order tends to
   * 0.5.
   */
  public InversionReport inversionRate(UUID eventId) {
    Map<String, Object> row =
        jdbc.queryForMap(
            """
            with ordered as (
              select user_ref,
                     row_number() over (order by joined_at, user_ref)   as arrival_rank,
                     row_number() over (order by admitted_at, id)       as admit_rank
              from admissions
              where event_id = :eventId
            ),
            pairs as (
              select count(*) as inversions
              from ordered a
              join ordered b
                on a.arrival_rank < b.arrival_rank
               and a.admit_rank   > b.admit_rank
            ),
            totals as (
              select count(*) as n from ordered
            )
            select totals.n                                                as admitted_count,
                   pairs.inversions                                        as inversions,
                   case when totals.n < 2 then 0
                        else pairs.inversions::numeric / (totals.n * (totals.n - 1) / 2)
                   end                                                     as inversion_rate
            from pairs, totals
            """,
            Map.of("eventId", eventId));

    return new InversionReport(
        ((Number) row.get("admitted_count")).longValue(),
        ((Number) row.get("inversions")).longValue(),
        ((Number) row.get("inversion_rate")).doubleValue());
  }

  /**
   * @param admittedCount how many buyers the rate was computed over
   * @param inversions pairs admitted in the opposite order to their arrival
   * @param inversionRate inversions as a share of all pairs; 0 is strict FIFO, 0.5 is random
   */
  public record InversionReport(long admittedCount, long inversions, double inversionRate) {}

  public long countFor(UUID eventId) {
    Long n =
        jdbc.queryForObject(
            "select count(*) from admissions where event_id = :eventId",
            Map.of("eventId", eventId),
            Long.class);
    return n == null ? 0 : n;
  }

  public void deleteFor(UUID eventId) {
    jdbc.update("delete from admissions where event_id = :eventId", Map.of("eventId", eventId));
  }
}
