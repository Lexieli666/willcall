package dev.willcall.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakePaymentGatewayTest {

  private FakePaymentGateway gateway(long timeoutMillis) {
    return new FakePaymentGateway(0, 0, timeoutMillis, "SUCCEED", new SimpleMeterRegistry());
  }

  @Test
  @DisplayName("a successful charge returns a reference")
  void successReturnsReference() {
    PaymentResult result =
        gateway(10).charge(UUID.randomUUID(), 1000, "USD", PaymentBehavior.SUCCEED);

    assertThat(result.succeeded()).isTrue();
    assertThat(result.reference()).startsWith("fakepay_");
  }

  @Test
  @DisplayName("charging the same order twice returns the first reference, not a second charge")
  void chargeIsIdempotentOnOrderId() {
    FakePaymentGateway gateway = gateway(10);
    UUID orderId = UUID.randomUUID();

    PaymentResult first = gateway.charge(orderId, 1000, "USD", PaymentBehavior.SUCCEED);
    PaymentResult second = gateway.charge(orderId, 1000, "USD", PaymentBehavior.SUCCEED);

    assertThat(second.reference()).isEqualTo(first.reference());
  }

  @Test
  @DisplayName("succeed-after-timeout records the charge before reporting the timeout")
  void succeedAfterTimeoutIsVisibleToARetry() {
    FakePaymentGateway gateway = gateway(5);
    UUID orderId = UUID.randomUUID();

    PaymentResult first =
        gateway.charge(orderId, 1000, "USD", PaymentBehavior.SUCCEED_AFTER_TIMEOUT);
    assertThat(first.status()).isEqualTo(PaymentResult.Status.TIMED_OUT);

    // This is the whole reason checkout is idempotent: the money moved even though the caller
    // was told nothing did.
    PaymentResult retry =
        gateway.charge(orderId, 1000, "USD", PaymentBehavior.SUCCEED_AFTER_TIMEOUT);
    assertThat(retry.succeeded()).isTrue();
  }

  @Test
  @DisplayName("a decline carries a failure code and no reference")
  void declineHasNoReference() {
    PaymentResult result =
        gateway(5).charge(UUID.randomUUID(), 1000, "USD", PaymentBehavior.DECLINE);

    assertThat(result.status()).isEqualTo(PaymentResult.Status.DECLINED);
    assertThat(result.reference()).isNull();
    assertThat(result.failureCode()).isEqualTo("card_declined");
  }

  @Test
  @DisplayName("an unknown behaviour string falls back rather than throwing")
  void unknownBehaviourFallsBack() {
    assertThat(PaymentBehavior.parse("nonsense", PaymentBehavior.SUCCEED))
        .isEqualTo(PaymentBehavior.SUCCEED);
    assertThat(PaymentBehavior.parse(null, PaymentBehavior.DECLINE))
        .isEqualTo(PaymentBehavior.DECLINE);
    assertThat(PaymentBehavior.parse("  decline  ", PaymentBehavior.SUCCEED))
        .isEqualTo(PaymentBehavior.DECLINE);
  }
}
