package dev.willcall.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * Resolves the opaque buyer reference every reservation call is scoped to.
 *
 * <p>In Phase 1 the client supplies it directly in {@code X-Willcall-User}. From Phase 3 the
 * waiting room issues a signed admission token and the reference is read from that instead, so a
 * buyer cannot mint identities to get more queue positions. The interface stays the same so that
 * change touches one class.
 */
public final class BuyerIdentity {

  public static final String HEADER = "X-Willcall-User";

  // Opaque to the server, but bounded: an unbounded identifier is an index-bloat vector and a
  // log-injection one. 8-64 characters of URL-safe text.
  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_.:-]{8,64}$");

  private BuyerIdentity() {}

  public static String require(HttpServletRequest request) {
    String value = request.getHeader(HEADER);
    if (value == null || value.isBlank()) {
      throw new ApiException(
          ErrorCode.MISSING_USER_REF, "Send a buyer reference in the " + HEADER + " header");
    }
    if (!VALID.matcher(value).matches()) {
      throw new ApiException(
          ErrorCode.MISSING_USER_REF,
          HEADER + " must be 8-64 characters of letters, digits, dot, colon, dash or underscore");
    }
    return value;
  }
}
