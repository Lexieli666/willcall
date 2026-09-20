package dev.willcall.reservation.service;

import dev.willcall.payment.PaymentBehavior;
import dev.willcall.payment.PaymentGateway;
import dev.willcall.payment.PaymentResult;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import dev.willcall.reservation.domain.Order;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates checkout: prepare, charge, settle.
 *
 * <p>This lives in its own bean rather than as a method on {@link ReservationService} for a reason
 * that cost a debugging session: Spring's {@code @Transactional} works through a proxy, so a
 * transactional method invoked from another method of the <em>same</em> bean runs with no
 * transaction. As one class, {@code checkout()} called {@code prepareCheckout()} directly, the
 * {@code SELECT ... FOR UPDATE} inside it ran in autocommit and released its locks immediately, and
 * the outbox write — which demands an ambient transaction — failed loudly. It failing loudly was
 * luck; the same mistake in a method without that guard would have produced a lock that looked
 * taken and was not.
 *
 * <p>The gateway call sits deliberately between the two transactions. Charging while holding seat
 * row locks would serialise the whole event behind the slowest card in the queue.
 */
@Service
public class CheckoutService {

  private final ReservationService reservations;
  private final PaymentGateway paymentGateway;

  public CheckoutService(ReservationService reservations, PaymentGateway paymentGateway) {
    this.reservations = reservations;
    this.paymentGateway = paymentGateway;
  }

  public Order checkout(UUID holdGroupId, String userRef, PaymentBehavior behavior) {
    ReservationService.CheckoutDraft draft = reservations.prepareCheckout(holdGroupId, userRef);

    PaymentResult payment =
        paymentGateway.charge(draft.orderId(), draft.totalCents(), draft.currency(), behavior);

    // Settlement returns rather than throws so that the seat release on a decline commits.
    // Translating to HTTP happens here, after that transaction has ended.
    return switch (reservations.settleCheckout(draft, payment)) {
      case ReservationService.Settlement.Confirmed confirmed -> confirmed.order();

      case ReservationService.Settlement.Declined declined ->
          throw new ApiException(
              ErrorCode.PAYMENT_DECLINED,
              "The payment was declined and the seats went back on sale",
              Map.of(
                  "orderId",
                  declined.orderId().toString(),
                  "failureCode",
                  declined.failureCode() == null ? "declined" : declined.failureCode()));

      case ReservationService.Settlement.TimedOut timedOut ->
          throw new ApiException(
              ErrorCode.PAYMENT_TIMEOUT,
              "The payment gateway did not answer. Your seats are still held; retry with the same"
                  + " Idempotency-Key.",
              Map.of("orderId", timedOut.orderId().toString()),
              2);

      case ReservationService.Settlement.SeatsLost lost ->
          throw new ApiException(
              ErrorCode.HOLD_EXPIRED,
              "Your payment went through but the seats were released first. A refund has been"
                  + " queued.",
              Map.of("orderId", lost.orderId().toString(), "refundRequired", true));
    };
  }
}
