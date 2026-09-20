package dev.willcall.catalog.store;

import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.domain.SeatStatus;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * All seat reads and writes.
 *
 * <p><b>The locking rule this repository exists to enforce.</b> A seat's status is only ever
 * written while its row is locked, and rows are always locked in ascending seat-id order. Ascending
 * order is not cosmetic: two buyers asking for overlapping multi-seat blocks would otherwise take
 * the same two locks in opposite orders and deadlock. The lock order across the whole reservation
 * core is holds first, then seats, and both by seat id ascending.
 *
 * <p>There is no unlocked read-then-write path here. {@link #lockForAcquisition} is the only way to
 * obtain seats for a state change, and it returns only rows it holds a lock on.
 */
@Repository
public class SeatRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public SeatRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<Seat> SEAT =
      (ResultSet rs, int i) ->
          new Seat(
              rs.getObject("id", UUID.class),
              rs.getObject("event_id", UUID.class),
              rs.getObject("row_id", UUID.class),
              rs.getObject("price_tier_id", UUID.class),
              rs.getInt("seat_number"),
              rs.getString("label"),
              SeatStatus.valueOf(rs.getString("status")),
              rs.getLong("version"),
              rs.getTimestamp("updated_at").toInstant());

  private static final String SELECT_COLUMNS =
      "id, event_id, row_id, price_tier_id, seat_number, label, status, version, updated_at";

  /**
   * Locks the given seats for update, in ascending id order.
   *
   * <p>Plain {@code FOR UPDATE}, not {@code SKIP LOCKED}: the caller asked for these exact seats,
   * so skipping a contended one would silently hand back a different answer than the one requested.
   * Waiting and then discovering the seat was taken is the correct outcome, and it is bounded by
   * the lock-wait timeout.
   */
  public List<Seat> lockForAcquisition(List<UUID> seatIds) {
    if (seatIds.isEmpty()) return List.of();
    return jdbc.query(
        "select " + SELECT_COLUMNS + " from seats where id in (:ids) order by id for update",
        new MapSqlParameterSource("ids", seatIds),
        SEAT);
  }

  /**
   * Claims up to {@code quantity} available seats anywhere in the event, locking each one.
   *
   * <p>{@code SKIP LOCKED} is right here and wrong in {@link #lockForAcquisition}: the caller asked
   * for "any free seat", so a seat another transaction is already working on is simply not one of
   * the free ones. Without it, 10,000 simultaneous requests would queue behind whichever
   * transaction holds the first row and the flash sale would serialise completely.
   */
  public List<Seat> claimAnyAvailable(UUID eventId, int quantity) {
    return jdbc.query(
        """
        select %s from seats
        where event_id = :eventId and status = 'AVAILABLE'
        order by id
        limit :quantity
        for update skip locked
        """
            .formatted(SELECT_COLUMNS),
        new MapSqlParameterSource().addValue("eventId", eventId).addValue("quantity", quantity),
        SEAT);
  }

  /**
   * Claims {@code quantity} adjacent available seats within a single row.
   *
   * <p>Phase 1 implementation: a linear scan over each row's seats, expressed in SQL as a window
   * function so the whole search is one round trip. Phase 2 replaces the search with a per-row
   * segment tree and publishes the crossover; this stays as the reference implementation the
   * property test compares against.
   */
  public List<Seat> claimContiguousInAnyRow(UUID eventId, int quantity) {
    List<UUID> candidate =
        jdbc.query(
            """
            with numbered as (
              select id, row_id, seat_number, status,
                     seat_number - row_number() over (partition by row_id order by seat_number)
                       as run_key
              from seats
              where event_id = :eventId and status = 'AVAILABLE'
            ),
            runs as (
              select row_id, run_key, min(seat_number) as start_number, count(*) as run_length
              from numbered
              group by row_id, run_key
              having count(*) >= :quantity
            ),
            best as (
              select row_id, start_number from runs
              order by run_length, row_id, start_number
              limit 1
            )
            select s.id
            from seats s
            join best b on b.row_id = s.row_id
            where s.event_id = :eventId
              and s.seat_number >= b.start_number
              and s.seat_number < b.start_number + :quantity
            order by s.id
            """,
            new MapSqlParameterSource().addValue("eventId", eventId).addValue("quantity", quantity),
            (rs, i) -> rs.getObject("id", UUID.class));

    if (candidate.size() < quantity) return List.of();

    // The scan above ran without locks, so the run may have been taken between the scan and
    // now. Re-lock and re-check; the caller treats a short or non-available result as a miss.
    return lockForAcquisition(candidate);
  }

  /**
   * Moves locked seats to a new status and bumps their version.
   *
   * <p>The {@code status = :expected} guard makes this a conditional update even though the rows
   * are already locked. The redundancy is deliberate: if a future caller forgets to lock, the
   * update degrades to compare-and-set and returns a row count the caller must check, rather than
   * silently overwriting somebody else's allocation.
   */
  public int transition(List<UUID> seatIds, SeatStatus expected, SeatStatus next) {
    if (seatIds.isEmpty()) return 0;
    return jdbc.update(
        """
        update seats
        set status = :next, version = version + 1, updated_at = now()
        where id in (:ids) and status = :expected
        """,
        new MapSqlParameterSource()
            .addValue("ids", seatIds)
            .addValue("expected", expected.name())
            .addValue("next", next.name()));
  }

  /**
   * The same conditional update as {@link #transition}, returning the new versions.
   *
   * <p>Callers that publish a delta need the post-update version, and reading it back in a second
   * statement would leave a window in which another transaction bumped it again.
   */
  public List<SeatVersion> transitionReturning(
      List<UUID> seatIds, SeatStatus expected, SeatStatus next) {
    if (seatIds.isEmpty()) return List.of();
    return jdbc.query(
        """
        update seats
        set status = :next, version = version + 1, updated_at = now()
        where id in (:ids) and status = :expected
        returning id, version
        """,
        new MapSqlParameterSource()
            .addValue("ids", seatIds)
            .addValue("expected", expected.name())
            .addValue("next", next.name()),
        (rs, i) -> new SeatVersion(rs.getObject("id", UUID.class), rs.getLong("version")));
  }

  /** A seat id with the version it now has. */
  public record SeatVersion(UUID seatId, long version) {}

  public List<Seat> findByEvent(UUID eventId) {
    return jdbc.query(
        "select "
            + SELECT_COLUMNS
            + " from seats where event_id = :eventId order by row_id, seat_number",
        Map.of("eventId", eventId),
        SEAT);
  }

  public List<Seat> findByIds(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jdbc.query(
        "select " + SELECT_COLUMNS + " from seats where id in (:ids) order by id",
        new MapSqlParameterSource("ids", ids),
        SEAT);
  }

  public Map<SeatStatus, Integer> countByStatus(UUID eventId) {
    return jdbc
        .query(
            "select status, count(*) as n from seats where event_id = :eventId group by status",
            Map.of("eventId", eventId),
            (rs, i) -> Map.entry(SeatStatus.valueOf(rs.getString("status")), rs.getInt("n")))
        .stream()
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public int countAvailable(UUID eventId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from seats where event_id = :eventId and status = 'AVAILABLE'",
            Map.of("eventId", eventId),
            Integer.class);
    return n == null ? 0 : n;
  }

  /** Bulk insert used by the seat generator; one statement per row of the venue. */
  public void insertSeats(UUID eventId, UUID rowId, UUID priceTierId, String rowLabel, int count) {
    jdbc.update(
        """
        insert into seats (event_id, row_id, price_tier_id, seat_number, label, status)
        select :eventId, :rowId, :tierId, n, :rowLabel || '-' || n, 'AVAILABLE'
        from generate_series(1, :count) as n
        """,
        new MapSqlParameterSource()
            .addValue("eventId", eventId)
            .addValue("rowId", rowId)
            .addValue("tierId", priceTierId)
            .addValue("rowLabel", rowLabel)
            .addValue("count", count));
  }
}
