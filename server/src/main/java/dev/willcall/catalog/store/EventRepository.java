package dev.willcall.catalog.store;

import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.domain.EventStatus;
import dev.willcall.catalog.domain.PriceTier;
import dev.willcall.catalog.domain.SeatRow;
import dev.willcall.catalog.domain.Section;
import dev.willcall.catalog.domain.Venue;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public EventRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<Event> EVENT =
      (ResultSet rs, int i) ->
          new Event(
              rs.getObject("id", UUID.class),
              rs.getObject("venue_id", UUID.class),
              rs.getString("name"),
              rs.getTimestamp("starts_at").toInstant(),
              rs.getTimestamp("sales_open_at").toInstant(),
              rs.getInt("capacity"),
              rs.getInt("hold_ttl_seconds"),
              rs.getInt("max_seats_per_order"),
              EventStatus.valueOf(rs.getString("status")),
              rs.getLong("last_sequence"));

  public UUID insertVenue(String name, String timezone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into venues (id, name, timezone) values (:id, :name, :tz)",
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("name", name)
            .addValue("tz", timezone));
    return id;
  }

  public Optional<Venue> findVenue(UUID id) {
    return jdbc
        .query(
            "select id, name, timezone from venues where id = :id",
            Map.of("id", id),
            (rs, i) ->
                new Venue(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("timezone")))
        .stream()
        .findFirst();
  }

  public UUID insertEvent(
      UUID venueId,
      String name,
      Instant startsAt,
      Instant salesOpenAt,
      int capacity,
      int holdTtlSeconds,
      int maxSeatsPerOrder,
      EventStatus status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into events (id, venue_id, name, starts_at, sales_open_at, capacity,
                            hold_ttl_seconds, max_seats_per_order, status)
        values (:id, :venueId, :name, :startsAt, :salesOpenAt, :capacity,
                :ttl, :maxSeats, :status)
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("venueId", venueId)
            .addValue("name", name)
            .addValue("startsAt", java.sql.Timestamp.from(startsAt))
            .addValue("salesOpenAt", java.sql.Timestamp.from(salesOpenAt))
            .addValue("capacity", capacity)
            .addValue("ttl", holdTtlSeconds)
            .addValue("maxSeats", maxSeatsPerOrder)
            .addValue("status", status.name()));
    return id;
  }

  public Optional<Event> find(UUID id) {
    return jdbc
        .query(
            """
            select id, venue_id, name, starts_at, sales_open_at, capacity, hold_ttl_seconds,
                   max_seats_per_order, status, last_sequence
            from events where id = :id
            """,
            Map.of("id", id),
            EVENT)
        .stream()
        .findFirst();
  }

  public List<Event> findAllOnSale() {
    return jdbc.query(
        """
        select id, venue_id, name, starts_at, sales_open_at, capacity, hold_ttl_seconds,
               max_seats_per_order, status, last_sequence
        from events where status = 'ON_SALE' order by starts_at
        """,
        EVENT);
  }

  public void updateStatus(UUID eventId, EventStatus status) {
    jdbc.update(
        "update events set status = :status where id = :id",
        Map.of("status", status.name(), "id", eventId));
  }

  public void updateCapacity(UUID eventId, int capacity) {
    jdbc.update(
        "update events set capacity = :capacity where id = :id",
        Map.of("capacity", capacity, "id", eventId));
  }

  public UUID insertPriceTier(UUID eventId, String name, int amountCents, String currency) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into price_tiers (id, event_id, name, amount_cents, currency)
        values (:id, :eventId, :name, :amount, :currency)
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("eventId", eventId)
            .addValue("name", name)
            .addValue("amount", amountCents)
            .addValue("currency", currency));
    return id;
  }

  public List<PriceTier> findPriceTiers(UUID eventId) {
    return jdbc.query(
        """
        select id, event_id, name, amount_cents, currency
        from price_tiers where event_id = :eventId order by amount_cents desc
        """,
        Map.of("eventId", eventId),
        (rs, i) ->
            new PriceTier(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("name"),
                rs.getInt("amount_cents"),
                rs.getString("currency")));
  }

  public UUID insertSection(UUID eventId, String name, int displayOrder) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into sections (id, event_id, name, display_order)
        values (:id, :eventId, :name, :order)
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("eventId", eventId)
            .addValue("name", name)
            .addValue("order", displayOrder));
    return id;
  }

  public List<Section> findSections(UUID eventId) {
    return jdbc.query(
        "select id, event_id, name, display_order from sections where event_id = :eventId order by display_order",
        Map.of("eventId", eventId),
        (rs, i) ->
            new Section(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("name"),
                rs.getInt("display_order")));
  }

  public UUID insertRow(
      UUID sectionId, UUID eventId, String label, int displayOrder, int seatCount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into seat_rows (id, section_id, event_id, label, display_order, seat_count)
        values (:id, :sectionId, :eventId, :label, :order, :count)
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("sectionId", sectionId)
            .addValue("eventId", eventId)
            .addValue("label", label)
            .addValue("order", displayOrder)
            .addValue("count", seatCount));
    return id;
  }

  public List<SeatRow> findRows(UUID eventId) {
    return jdbc.query(
        """
        select id, section_id, event_id, label, display_order, seat_count
        from seat_rows where event_id = :eventId order by display_order
        """,
        Map.of("eventId", eventId),
        (rs, i) ->
            new SeatRow(
                rs.getObject("id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("label"),
                rs.getInt("display_order"),
                rs.getInt("seat_count")));
  }
}
