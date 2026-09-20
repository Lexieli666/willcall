package dev.willcall.reservation.store;

import dev.willcall.reservation.domain.Order;
import dev.willcall.reservation.domain.OrderLine;
import dev.willcall.reservation.domain.OrderStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public OrderRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertPending(
      UUID orderId,
      UUID eventId,
      UUID holdGroupId,
      String userRef,
      int totalCents,
      String currency) {
    jdbc.update(
        """
        insert into orders (id, event_id, hold_group_id, user_ref, status, total_cents, currency)
        values (:id, :eventId, :holdGroupId, :userRef, 'PENDING', :total, :currency)
        """,
        new MapSqlParameterSource()
            .addValue("id", orderId)
            .addValue("eventId", eventId)
            .addValue("holdGroupId", holdGroupId)
            .addValue("userRef", userRef)
            .addValue("total", totalCents)
            .addValue("currency", currency));
  }

  public void markConfirmed(UUID orderId, String paymentReference) {
    jdbc.update(
        """
        update orders set status = 'CONFIRMED', confirmed_at = now(), payment_reference = :ref
        where id = :id and status = 'PENDING'
        """,
        Map.of("id", orderId, "ref", paymentReference));
  }

  public void markFailed(UUID orderId, String failureCode) {
    jdbc.update(
        "update orders set status = 'FAILED', failure_code = :code where id = :id and status = 'PENDING'",
        Map.of("id", orderId, "code", failureCode));
  }

  /**
   * Order lines exist only for confirmed orders. That is what lets the unique index on {@code
   * order_lines.seat_id} mean "this seat is sold" with no status column to consult and no cleanup
   * after a declined payment.
   */
  public void insertLines(UUID orderId, List<UUID> seatIds, Map<UUID, Integer> priceBySeat) {
    SqlParameterSource[] batch =
        seatIds.stream()
            .map(
                seatId ->
                    (SqlParameterSource)
                        new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("orderId", orderId)
                            .addValue("seatId", seatId)
                            .addValue("price", priceBySeat.getOrDefault(seatId, 0)))
            .toArray(SqlParameterSource[]::new);

    jdbc.batchUpdate(
        """
        insert into order_lines (id, order_id, seat_id, price_cents)
        values (:id, :orderId, :seatId, :price)
        """,
        batch);
  }

  public Optional<Order> find(UUID orderId) {
    List<Order> orders =
        jdbc.query(
            """
            select id, event_id, hold_group_id, user_ref, status, total_cents, currency,
                   payment_reference, failure_code, created_at, confirmed_at
            from orders where id = :id
            """,
            Map.of("id", orderId),
            (rs, i) ->
                new Order(
                    rs.getObject("id", UUID.class),
                    rs.getObject("event_id", UUID.class),
                    rs.getObject("hold_group_id", UUID.class),
                    rs.getString("user_ref"),
                    OrderStatus.valueOf(rs.getString("status")),
                    rs.getInt("total_cents"),
                    rs.getString("currency"),
                    rs.getString("payment_reference"),
                    rs.getString("failure_code"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("confirmed_at") == null
                        ? null
                        : rs.getTimestamp("confirmed_at").toInstant(),
                    List.of()));
    if (orders.isEmpty()) return Optional.empty();
    Order order = orders.get(0);
    return Optional.of(
        new Order(
            order.id(),
            order.eventId(),
            order.holdGroupId(),
            order.userRef(),
            order.status(),
            order.totalCents(),
            order.currency(),
            order.paymentReference(),
            order.failureCode(),
            order.createdAt(),
            order.confirmedAt(),
            findLines(orderId)));
  }

  public List<OrderLine> findLines(UUID orderId) {
    return jdbc.query(
        "select id, order_id, seat_id, price_cents from order_lines where order_id = :id order by seat_id",
        Map.of("id", orderId),
        (rs, i) ->
            new OrderLine(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("seat_id", UUID.class),
                rs.getInt("price_cents")));
  }

  /**
   * The pending order for a hold group, if one exists.
   *
   * <p>Checkout reuses it rather than creating a new one on every attempt. The payment gateway keys
   * its own idempotency on the order id, so a fresh id per retry would turn "retry after a timeout"
   * into "charge twice".
   */
  public Optional<UUID> findPendingOrderForHoldGroup(UUID holdGroupId) {
    return jdbc
        .query(
            "select id from orders where hold_group_id = :id and status = 'PENDING' order by created_at limit 1",
            Map.of("id", holdGroupId),
            (rs, i) -> rs.getObject("id", UUID.class))
        .stream()
        .findFirst();
  }

  public Optional<UUID> findConfirmedOrderForHoldGroup(UUID holdGroupId) {
    return jdbc
        .query(
            "select id from orders where hold_group_id = :id and status = 'CONFIRMED' limit 1",
            Map.of("id", holdGroupId),
            (rs, i) -> rs.getObject("id", UUID.class))
        .stream()
        .findFirst();
  }

  public List<Order> findByUser(UUID eventId, String userRef) {
    return jdbc.query(
        """
        select id, event_id, hold_group_id, user_ref, status, total_cents, currency,
               payment_reference, failure_code, created_at, confirmed_at
        from orders where event_id = :eventId and user_ref = :userRef
        order by created_at desc limit 50
        """,
        Map.of("eventId", eventId, "userRef", userRef),
        (rs, i) ->
            new Order(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("hold_group_id", UUID.class),
                rs.getString("user_ref"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getInt("total_cents"),
                rs.getString("currency"),
                rs.getString("payment_reference"),
                rs.getString("failure_code"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("confirmed_at") == null
                    ? null
                    : rs.getTimestamp("confirmed_at").toInstant(),
                List.of()));
  }

  public int countConfirmedSeats(UUID eventId) {
    Integer n =
        jdbc.queryForObject(
            """
            select count(*) from order_lines ol
            join orders o on o.id = ol.order_id
            where o.event_id = :eventId and o.status = 'CONFIRMED'
            """,
            Map.of("eventId", eventId),
            Integer.class);
    return n == null ? 0 : n;
  }
}
