package dev.willcall.payment;

import java.util.UUID;

public interface PaymentGateway {

  /**
   * Charges for an order.
   *
   * @param orderId used as the gateway's own idempotency key, so a retry of the same order never
   *     produces a second charge even if the first attempt timed out
   */
  PaymentResult charge(UUID orderId, int amountCents, String currency, PaymentBehavior behavior);
}
