package dev.willcall.reservation.api;

import dev.willcall.payment.PaymentBehavior;
import dev.willcall.platform.web.BuyerIdentity;
import dev.willcall.reservation.domain.Order;
import dev.willcall.reservation.service.CheckoutService;
import dev.willcall.reservation.service.IdempotencyService;
import dev.willcall.reservation.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final ReservationService reservations;
  private final CheckoutService checkout;
  private final IdempotencyService idempotency;

  public OrderController(
      ReservationService reservations, CheckoutService checkout, IdempotencyService idempotency) {
    this.reservations = reservations;
    this.checkout = checkout;
    this.idempotency = idempotency;
  }

  /**
   * What a confirmation asks for.
   *
   * @param paymentBehavior how the fake gateway should behave. Present so a load test or a demo can
   *     exercise decline and timeout paths without a second deployment; a real gateway would ignore
   *     it.
   */
  public record ConfirmRequest(@NotNull UUID holdId, String paymentBehavior) {}

  public record OrderResponse(
      UUID orderId,
      UUID eventId,
      String status,
      int totalCents,
      String currency,
      List<UUID> seatIds,
      String paymentReference,
      Instant confirmedAt) {

    static OrderResponse of(Order order) {
      return new OrderResponse(
          order.id(),
          order.eventId(),
          order.status().name(),
          order.totalCents(),
          order.currency(),
          order.lines().stream().map(dev.willcall.reservation.domain.OrderLine::seatId).toList(),
          order.paymentReference(),
          order.confirmedAt());
    }
  }

  /**
   * Confirms a hold and takes payment.
   *
   * <p>{@code Idempotency-Key} is not optional in practice: the gateway can succeed and still time
   * out, and the only safe retry is one that carries the same key. The header is accepted as
   * optional so that a caller that omits it gets the unguarded behaviour rather than a 400 it
   * cannot interpret, but every client in this repository sends one.
   */
  @PostMapping
  public ResponseEntity<?> confirm(
      @Valid @RequestBody ConfirmRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {

    String userRef = BuyerIdentity.require(request);
    PaymentBehavior behavior = PaymentBehavior.parse(body.paymentBehavior(), null);

    IdempotencyService.Result<OrderResponse> result =
        idempotency.execute(
            idempotencyKey,
            userRef,
            "POST /api/orders",
            body,
            () -> {
              Order order = checkout.checkout(body.holdId(), userRef, behavior);
              return new IdempotencyService.Outcome<>(201, OrderResponse.of(order), order.id());
            });

    if (result.replayed()) {
      return ResponseEntity.status(result.status())
          .header("Idempotency-Replayed", "true")
          .contentType(MediaType.APPLICATION_JSON)
          .body(result.replay().body());
    }
    return ResponseEntity.status(result.status()).body(result.body());
  }

  @GetMapping("/{orderId}")
  public OrderResponse get(@PathVariable UUID orderId, HttpServletRequest request) {
    BuyerIdentity.require(request);
    return reservations
        .findOrder(orderId)
        .map(OrderResponse::of)
        .orElseThrow(
            () ->
                new dev.willcall.platform.web.ApiException(
                    dev.willcall.platform.web.ErrorCode.ORDER_NOT_FOUND));
  }
}
