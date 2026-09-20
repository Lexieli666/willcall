package dev.willcall.payment;

/**
 * How the fake gateway should behave for a request.
 *
 * <p>{@link #SUCCEED_AFTER_TIMEOUT} is the interesting one and the reason checkout is idempotent.
 * The client sees a timeout and retries; the original charge succeeded anyway. Any design that
 * treats a timeout as a failure double-charges here, and no amount of testing the other three modes
 * would reveal it.
 */
public enum PaymentBehavior {
  SUCCEED,
  DECLINE,
  TIMEOUT,
  SUCCEED_AFTER_TIMEOUT;

  public static PaymentBehavior parse(String value, PaymentBehavior fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }
}
