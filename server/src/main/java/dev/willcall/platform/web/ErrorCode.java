package dev.willcall.platform.web;

import org.springframework.http.HttpStatus;

/**
 * Every failure the API can return, with the status code it maps to. Keeping the mapping in one
 * enum is what stops "seat already taken" from being a 400 in one handler and a 409 in another,
 * which is the difference between a client that can retry correctly and one that cannot.
 *
 * <p>The distinction that matters most to a caller under load:
 *
 * <ul>
 *   <li><b>409</b> — a legitimate answer. Somebody else got there first. Do not retry the same
 *       request; ask for a different seat.
 *   <li><b>429</b> — you are going too fast. Retry after the interval in {@code Retry-After}.
 *   <li><b>503</b> — the service is shedding load. Retry with backoff; the request never ran.
 *   <li><b>422</b> — your request is contradictory (an idempotency key reused with a different
 *       body). Retrying is pointless until the client is fixed.
 * </ul>
 */
public enum ErrorCode {
  EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Event not found"),
  SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "Seat not found"),
  HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "Hold not found"),
  ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),

  EVENT_NOT_ON_SALE(HttpStatus.CONFLICT, "This event is not on sale"),
  SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "One or more of those seats is no longer available"),
  NOT_ENOUGH_CONTIGUOUS_SEATS(HttpStatus.CONFLICT, "There is no block of that many seats together"),
  SOLD_OUT(HttpStatus.CONFLICT, "There are no seats left"),
  ALREADY_HOLDING_SEAT(HttpStatus.CONFLICT, "You already hold one of those seats"),
  TOO_MANY_SEATS(HttpStatus.CONFLICT, "That is more seats than this event allows in one order"),
  IDEMPOTENT_REQUEST_IN_PROGRESS(
      HttpStatus.CONFLICT, "An identical request is still being processed"),

  HOLD_EXPIRED(HttpStatus.GONE, "That hold expired and the seats went back on sale"),
  HOLD_NOT_ACTIVE(HttpStatus.GONE, "That hold is no longer active"),

  IDEMPOTENCY_KEY_REUSED(
      HttpStatus.UNPROCESSABLE_ENTITY,
      "That Idempotency-Key was already used for a different request"),
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "The request is not valid"),
  MISSING_USER_REF(HttpStatus.BAD_REQUEST, "The request carries no buyer identity"),

  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
  NOT_ADMITTED(HttpStatus.FORBIDDEN, "You have not been admitted from the waiting room yet"),

  PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "The payment was declined"),
  PAYMENT_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The payment gateway did not answer in time"),

  OVERLOADED(HttpStatus.SERVICE_UNAVAILABLE, "The service is shedding load; retry shortly"),
  INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");

  private final HttpStatus status;
  private final String defaultDetail;

  ErrorCode(HttpStatus status, String defaultDetail) {
    this.status = status;
    this.defaultDetail = defaultDetail;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultDetail() {
    return defaultDetail;
  }

  /** Lower-snake-case, which is what the wire format uses. */
  public String wireCode() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
