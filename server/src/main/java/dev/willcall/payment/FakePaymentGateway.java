package dev.willcall.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a payment processor.
 *
 * <p>It is idempotent on {@code orderId} in the same way a real gateway is: a second charge for an
 * order that already succeeded returns the original reference rather than charging again. That is
 * what makes {@link PaymentBehavior#SUCCEED_AFTER_TIMEOUT} survivable — the retry after the timeout
 * finds the completed charge instead of creating a second one.
 *
 * <p>The default latency is configurable because checkout's p99 budget is stated relative to it: a
 * 50 ms gateway is assumed, and a result that does not say which latency was used is not a result.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

  private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

  private final Map<UUID, String> completedCharges = new ConcurrentHashMap<>();
  private final long baseLatencyMillis;
  private final long jitterMillis;
  private final long timeoutMillis;
  private final PaymentBehavior defaultBehavior;
  private final Timer chargeTimer;

  public FakePaymentGateway(
      @Value("${willcall.payment.latency-ms:50}") long baseLatencyMillis,
      @Value("${willcall.payment.jitter-ms:10}") long jitterMillis,
      @Value("${willcall.payment.timeout-ms:2000}") long timeoutMillis,
      @Value("${willcall.payment.default-behavior:SUCCEED}") String defaultBehavior,
      MeterRegistry meterRegistry) {
    this.baseLatencyMillis = baseLatencyMillis;
    this.jitterMillis = jitterMillis;
    this.timeoutMillis = timeoutMillis;
    this.defaultBehavior = PaymentBehavior.parse(defaultBehavior, PaymentBehavior.SUCCEED);
    this.chargeTimer =
        Timer.builder("willcall.payment.charge")
            .description("Time spent in the payment gateway")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
  }

  @Override
  public PaymentResult charge(
      UUID orderId, int amountCents, String currency, PaymentBehavior behavior) {
    PaymentBehavior effective = behavior == null ? defaultBehavior : behavior;
    long start = System.nanoTime();
    try {
      return doCharge(orderId, amountCents, currency, effective);
    } finally {
      chargeTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  private PaymentResult doCharge(
      UUID orderId, int amountCents, String currency, PaymentBehavior behavior) {
    String existing = completedCharges.get(orderId);
    if (existing != null) {
      // A real gateway keyed on the order id would do exactly this. It is the reason a timeout
      // followed by a retry cannot double-charge.
      return new PaymentResult(PaymentResult.Status.SUCCEEDED, existing, null);
    }

    sleep(baseLatencyMillis + ThreadLocalRandom.current().nextLong(jitterMillis + 1));

    return switch (behavior) {
      case SUCCEED -> {
        String reference = reference(orderId);
        completedCharges.put(orderId, reference);
        yield new PaymentResult(PaymentResult.Status.SUCCEEDED, reference, null);
      }
      case DECLINE -> new PaymentResult(PaymentResult.Status.DECLINED, null, "card_declined");
      case TIMEOUT -> {
        sleep(timeoutMillis);
        yield new PaymentResult(PaymentResult.Status.TIMED_OUT, null, "gateway_timeout");
      }
      case SUCCEED_AFTER_TIMEOUT -> {
        // The charge lands, the caller never hears about it. Recording it before sleeping is
        // the whole point: the client's retry must find it.
        String reference = reference(orderId);
        completedCharges.put(orderId, reference);
        log.info(
            "fake gateway: charge {} succeeded but the response will time out, amount={} {}",
            reference,
            amountCents,
            currency);
        sleep(timeoutMillis);
        yield new PaymentResult(PaymentResult.Status.TIMED_OUT, null, "gateway_timeout");
      }
    };
  }

  private static String reference(UUID orderId) {
    return "fakepay_" + orderId.toString().replace("-", "").substring(0, 20);
  }

  private static void sleep(long millis) {
    if (millis <= 0) return;
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while talking to the payment gateway", e);
    }
  }

  /** Test seam: forget recorded charges between scenarios. */
  public void reset() {
    completedCharges.clear();
  }
}
