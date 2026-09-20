package dev.willcall.reservation.store;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Transactional outbox.
 *
 * <p>A seat change and the message announcing it are written in the same transaction, so a browser
 * can never be told about a state the database does not hold, and a committed change can never fail
 * to be announced. The relay that drains this table assigns the per-event sequence number; the
 * writer deliberately does not, because a per-event counter on the hot path would serialise every
 * hold for an event onto one row.
 */
@Repository
public class OutboxRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public OutboxRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Entry(
      long id,
      UUID eventId,
      String aggregateType,
      UUID aggregateId,
      String type,
      String payload,
      Long sequenceNo) {}

  public void append(
      UUID eventId, String aggregateType, UUID aggregateId, String type, String payloadJson) {
    jdbc.update(
        """
        insert into outbox (event_id, aggregate_type, aggregate_id, type, payload)
        values (:eventId, :aggregateType, :aggregateId, :type, cast(:payload as jsonb))
        """,
        new MapSqlParameterSource()
            .addValue("eventId", eventId)
            .addValue("aggregateType", aggregateType)
            .addValue("aggregateId", aggregateId)
            .addValue("type", type)
            .addValue("payload", payloadJson));
  }

  public void appendAll(List<Entry> entries) {
    if (entries.isEmpty()) return;
    SqlParameterSource[] batch =
        entries.stream()
            .map(
                e ->
                    (SqlParameterSource)
                        new MapSqlParameterSource()
                            .addValue("eventId", e.eventId())
                            .addValue("aggregateType", e.aggregateType())
                            .addValue("aggregateId", e.aggregateId())
                            .addValue("type", e.type())
                            .addValue("payload", e.payload()))
            .toArray(SqlParameterSource[]::new);
    jdbc.batchUpdate(
        """
        insert into outbox (event_id, aggregate_type, aggregate_id, type, payload)
        values (:eventId, :aggregateType, :aggregateId, :type, cast(:payload as jsonb))
        """,
        batch);
  }

  /**
   * Takes a per-event advisory lock for the duration of the transaction.
   *
   * <p>This is what keeps sequence numbers gap-free while every replica runs a relay. Without it,
   * two replicas draining the same event would assign numbers out of order and every connected
   * client would see a gap and resync — turning an optimisation into a stampede.
   */
  public boolean tryLockEvent(UUID eventId) {
    Boolean locked =
        jdbc.queryForObject(
            "select pg_try_advisory_xact_lock(hashtext(:key))",
            Map.of("key", "willcall-outbox:" + eventId),
            Boolean.class);
    return Boolean.TRUE.equals(locked);
  }

  public List<UUID> eventsWithPendingEntries(int limit) {
    return jdbc.query(
        """
        select distinct event_id from outbox
        where published_at is null
        limit :limit
        """,
        Map.of("limit", limit),
        (rs, i) -> rs.getObject("event_id", UUID.class));
  }

  public List<Entry> pendingForEvent(UUID eventId, int limit) {
    return jdbc.query(
        """
        select id, event_id, aggregate_type, aggregate_id, type, payload::text as payload, sequence_no
        from outbox
        where event_id = :eventId and published_at is null
        order by id
        limit :limit
        """,
        new MapSqlParameterSource().addValue("eventId", eventId).addValue("limit", limit),
        (rs, i) ->
            new Entry(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("type"),
                rs.getString("payload"),
                rs.getObject("sequence_no", Long.class)));
  }

  /**
   * Published entries after a given sequence number, for a reconnecting client.
   *
   * <p>Retained history only: the trimmer removes entries older than the retention window, so a
   * client that has been away longer gets a snapshot instead. The caller checks contiguity — a hole
   * here means part of what the client needs is already gone.
   */
  public List<Entry> publishedSince(UUID eventId, long afterSequence, int limit) {
    return jdbc.query(
        """
        select id, event_id, aggregate_type, aggregate_id, type, payload::text as payload, sequence_no
        from outbox
        where event_id = :eventId and sequence_no > :after and published_at is not null
        order by sequence_no
        limit :limit
        """,
        new MapSqlParameterSource()
            .addValue("eventId", eventId)
            .addValue("after", afterSequence)
            .addValue("limit", limit),
        (rs, i) ->
            new Entry(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("type"),
                rs.getString("payload"),
                rs.getObject("sequence_no", Long.class)));
  }

  /** Advances the event's sequence counter by {@code count} and returns the first value used. */
  public long reserveSequenceRange(UUID eventId, int count) {
    Long last =
        jdbc.queryForObject(
            "update events set last_sequence = last_sequence + :count where id = :id returning last_sequence",
            new MapSqlParameterSource().addValue("count", count).addValue("id", eventId),
            Long.class);
    long lastValue = last == null ? 0L : last;
    return lastValue - count + 1;
  }

  public void markPublished(long id, long sequenceNo) {
    jdbc.update(
        "update outbox set published_at = now(), sequence_no = :seq where id = :id",
        Map.of("id", id, "seq", sequenceNo));
  }

  public int deletePublishedBefore(java.time.Instant cutoff) {
    return jdbc.update(
        "delete from outbox where published_at is not null and published_at < :cutoff",
        Map.of("cutoff", java.sql.Timestamp.from(cutoff)));
  }

  public long countPending() {
    Long n =
        jdbc.getJdbcTemplate()
            .queryForObject("select count(*) from outbox where published_at is null", Long.class);
    return n == null ? 0L : n;
  }
}
