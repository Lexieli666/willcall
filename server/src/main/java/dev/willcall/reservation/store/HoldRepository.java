package dev.willcall.reservation.store;

import dev.willcall.reservation.domain.Hold;
import dev.willcall.reservation.domain.HoldGroup;
import dev.willcall.reservation.domain.HoldStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Hold storage.
 *
 * <p><b>Lock order.</b> Everything that resolves an existing hold — confirm, cancel, sweep — locks
 * hold rows first, ordered by seat id, and only then touches the seats. Acquisition is the one
 * exception and cannot deadlock against the others: it locks seats and then <em>inserts</em> hold
 * rows, so it never waits on a hold row another transaction already holds.
 */
@Repository
public class HoldRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public HoldRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<Hold> HOLD =
      (ResultSet rs, int i) ->
          new Hold(
              rs.getObject("id", UUID.class),
              rs.getObject("hold_group_id", UUID.class),
              rs.getObject("event_id", UUID.class),
              rs.getObject("seat_id", UUID.class),
              rs.getString("user_ref"),
              HoldStatus.valueOf(rs.getString("status")),
              rs.getTimestamp("expires_at").toInstant(),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("resolved_at") == null
                  ? null
                  : rs.getTimestamp("resolved_at").toInstant());

  private static final String HOLD_COLUMNS =
      "id, hold_group_id, event_id, seat_id, user_ref, status, expires_at, created_at, resolved_at";

  /** The same columns qualified, for the one query that joins holds to a subquery over holds. */
  private static final String HOLD_COLUMNS_QUALIFIED =
      "h.id, h.hold_group_id, h.event_id, h.seat_id, h.user_ref, h.status, h.expires_at,"
          + " h.created_at, h.resolved_at";

  public void insertGroup(
      UUID groupId, UUID eventId, String userRef, Instant expiresAt, int seatCount) {
    jdbc.update(
        """
        insert into hold_groups (id, event_id, user_ref, status, expires_at, seat_count)
        values (:id, :eventId, :userRef, 'ACTIVE', :expiresAt, :seatCount)
        """,
        new MapSqlParameterSource()
            .addValue("id", groupId)
            .addValue("eventId", eventId)
            .addValue("userRef", userRef)
            .addValue("expiresAt", Timestamp.from(expiresAt))
            .addValue("seatCount", seatCount));
  }

  /**
   * Inserts one hold row per seat.
   *
   * <p>A unique violation here means another transaction took the same seat between our lock and
   * this insert, which should be impossible while the seat row is locked. The caller lets the
   * exception propagate and roll the whole acquisition back: silently swallowing it is how an
   * oversell would get through.
   */
  public void insertHolds(
      UUID groupId, UUID eventId, String userRef, Instant expiresAt, List<UUID> seatIds) {
    SqlParameterSource[] batch =
        seatIds.stream()
            .map(
                seatId ->
                    (SqlParameterSource)
                        new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("groupId", groupId)
                            .addValue("eventId", eventId)
                            .addValue("seatId", seatId)
                            .addValue("userRef", userRef)
                            .addValue("expiresAt", Timestamp.from(expiresAt)))
            .toArray(SqlParameterSource[]::new);

    jdbc.batchUpdate(
        """
        insert into holds (id, hold_group_id, event_id, seat_id, user_ref, status, expires_at)
        values (:id, :groupId, :eventId, :seatId, :userRef, 'ACTIVE', :expiresAt)
        """,
        batch);
  }

  /** Locks the hold rows of a group, ordered by seat id. First step of confirm and cancel. */
  public List<Hold> lockGroupHolds(UUID groupId) {
    return jdbc.query(
        "select "
            + HOLD_COLUMNS
            + " from holds where hold_group_id = :groupId order by seat_id for update",
        Map.of("groupId", groupId),
        HOLD);
  }

  public Optional<HoldGroup> findGroup(UUID groupId) {
    List<HoldGroup> groups =
        jdbc.query(
            """
            select id, event_id, user_ref, status, expires_at, seat_count, created_at, resolved_at
            from hold_groups where id = :id
            """,
            Map.of("id", groupId),
            (rs, i) ->
                new HoldGroup(
                    rs.getObject("id", UUID.class),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("user_ref"),
                    HoldStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getInt("seat_count"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("resolved_at") == null
                        ? null
                        : rs.getTimestamp("resolved_at").toInstant(),
                    List.of()));
    if (groups.isEmpty()) return Optional.empty();
    HoldGroup group = groups.get(0);
    return Optional.of(withSeatIds(group));
  }

  private HoldGroup withSeatIds(HoldGroup group) {
    List<UUID> seatIds =
        jdbc.query(
            "select seat_id from holds where hold_group_id = :id order by seat_id",
            Map.of("id", group.id()),
            (rs, i) -> rs.getObject("seat_id", UUID.class));
    return new HoldGroup(
        group.id(),
        group.eventId(),
        group.userRef(),
        group.status(),
        group.expiresAt(),
        group.seatCount(),
        group.createdAt(),
        group.resolvedAt(),
        seatIds);
  }

  /**
   * Resolves every ACTIVE hold in a group.
   *
   * <p>Returns the number of rows changed. A caller that asked to confirm and got back fewer rows
   * than the group holds has lost a race with the sweeper and must roll back: this is the single
   * point where confirm-versus-expire is decided.
   */
  public int resolveGroupHolds(UUID groupId, HoldStatus next) {
    return jdbc.update(
        """
        update holds set status = :next, resolved_at = now()
        where hold_group_id = :groupId and status = 'ACTIVE'
        """,
        Map.of("next", next.name(), "groupId", groupId));
  }

  public int resolveGroup(UUID groupId, HoldStatus next) {
    return jdbc.update(
        """
        update hold_groups set status = :next, resolved_at = now()
        where id = :groupId and status = 'ACTIVE'
        """,
        Map.of("next", next.name(), "groupId", groupId));
  }

  /**
   * Pushes a group's expiry out to cover the payment window.
   *
   * <p>Only ever extends, never shortens: {@code greatest(expires_at, :until)} means a slow
   * checkout cannot accidentally shorten a hold that already had longer to run.
   */
  public int extendGroup(UUID groupId, Instant until) {
    int groups =
        jdbc.update(
            """
            update hold_groups set expires_at = greatest(expires_at, :until)
            where id = :id and status = 'ACTIVE'
            """,
            new MapSqlParameterSource()
                .addValue("id", groupId)
                .addValue("until", Timestamp.from(until)));
    jdbc.update(
        """
        update holds set expires_at = greatest(expires_at, :until)
        where hold_group_id = :id and status = 'ACTIVE'
        """,
        new MapSqlParameterSource()
            .addValue("id", groupId)
            .addValue("until", Timestamp.from(until)));
    return groups;
  }

  /**
   * Claims a batch of expired holds for the sweeper.
   *
   * <p>{@code SKIP LOCKED} is what lets every replica run the sweeper concurrently without
   * coordination: two sweepers never pick the same row, so expiry releases capacity exactly once
   * without a leader election.
   *
   * <p>The selection and the locking are two steps on purpose, and the reason is a measurement. As
   * one statement — {@code where status = 'ACTIVE' and expires_at <= now() order by seat_id} — the
   * planner serves the {@code ORDER BY} from {@code holds_single_active_per_seat} and filters out
   * everything that has not expired yet. With 50,000 live holds of which 50 had expired, that read
   * 50,060 buffers to return 50 rows, in 9.5 ms; the sweeper runs four times a second on every
   * replica, so that is 600,000 buffer touches a second spent finding almost nothing. The inner
   * query walks {@code holds_active_by_expiry} instead and stops at the batch size, and the outer
   * one locks those rows in {@code seat_id} order, which keeps the single lock order of ADR 0005
   * intact. Same population, same result: 0.26 ms and a few dozen buffers.
   *
   * <p>The outer query repeats the predicate rather than trusting the inner one. Under READ
   * COMMITTED, {@code FOR UPDATE} re-evaluates it after taking the lock, so a hold that was
   * confirmed between the two steps is dropped instead of being expired out from under its buyer.
   *
   * <p>The plan for both forms is captured in {@code load/results/*}{@code /query-plans/}.
   */
  public List<Hold> claimExpired(Instant now, int batchSize) {
    return jdbc.query(
        """
        with due as (
          select id from holds
          where status = 'ACTIVE' and expires_at <= :now
          order by expires_at
          limit :batchSize
        )
        select %s from holds h
        join due on due.id = h.id
        where h.status = 'ACTIVE' and h.expires_at <= :now
        order by h.seat_id
        for update of h skip locked
        """
            .formatted(HOLD_COLUMNS_QUALIFIED),
        new MapSqlParameterSource()
            .addValue("now", Timestamp.from(now))
            .addValue("batchSize", batchSize),
        HOLD);
  }

  public int markExpired(List<UUID> holdIds) {
    if (holdIds.isEmpty()) return 0;
    return jdbc.update(
        "update holds set status = 'EXPIRED', resolved_at = now() where id in (:ids) and status = 'ACTIVE'",
        new MapSqlParameterSource("ids", holdIds));
  }

  public int markGroupsExpired(List<UUID> groupIds) {
    if (groupIds.isEmpty()) return 0;
    return jdbc.update(
        """
        update hold_groups g set status = 'EXPIRED', resolved_at = now()
        where g.id in (:ids) and g.status = 'ACTIVE'
          and not exists (select 1 from holds h where h.hold_group_id = g.id and h.status = 'ACTIVE')
        """,
        new MapSqlParameterSource("ids", groupIds));
  }

  public List<HoldGroup> findActiveGroupsForUser(UUID eventId, String userRef) {
    List<HoldGroup> groups =
        jdbc.query(
            """
            select id, event_id, user_ref, status, expires_at, seat_count, created_at, resolved_at
            from hold_groups
            where event_id = :eventId and user_ref = :userRef and status = 'ACTIVE'
            order by created_at desc
            """,
            Map.of("eventId", eventId, "userRef", userRef),
            (rs, i) ->
                new HoldGroup(
                    rs.getObject("id", UUID.class),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("user_ref"),
                    HoldStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getInt("seat_count"),
                    rs.getTimestamp("created_at").toInstant(),
                    null,
                    List.of()));
    List<HoldGroup> withSeats = new ArrayList<>(groups.size());
    for (HoldGroup g : groups) withSeats.add(withSeatIds(g));
    return withSeats;
  }

  public int countActiveHoldsForUser(UUID eventId, String userRef) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from holds where event_id = :eventId and user_ref = :userRef and status = 'ACTIVE'",
            Map.of("eventId", eventId, "userRef", userRef),
            Integer.class);
    return n == null ? 0 : n;
  }

  public int countActive(UUID eventId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from holds where event_id = :eventId and status = 'ACTIVE'",
            Map.of("eventId", eventId),
            Integer.class);
    return n == null ? 0 : n;
  }
}
