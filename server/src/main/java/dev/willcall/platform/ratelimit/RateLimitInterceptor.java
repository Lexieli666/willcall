package dev.willcall.platform.ratelimit;

import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.BuyerIdentity;
import dev.willcall.platform.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the limiter to the endpoints that change state.
 *
 * <p>Reads are not limited. A buyer refreshing a seat map is not the problem, and limiting reads
 * would break the one thing somebody does while waiting.
 *
 * <p>The refusal is an {@link ApiException}, so it comes back as the same problem+json every other
 * error uses, with {@code Retry-After} attached. An interceptor that wrote its own response would
 * produce a body in a shape no client parses.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

  private final RateLimiter limiter;
  private final double holdsPerSecond;
  private final int holdsBurst;
  private final double ordersPerSecond;
  private final int ordersBurst;

  public RateLimitInterceptor(
      RateLimiter limiter,
      @Value("${willcall.ratelimit.holds-per-second:5}") double holdsPerSecond,
      @Value("${willcall.ratelimit.holds-burst:10}") int holdsBurst,
      @Value("${willcall.ratelimit.orders-per-second:2}") double ordersPerSecond,
      @Value("${willcall.ratelimit.orders-burst:5}") int ordersBurst) {
    this.limiter = limiter;
    this.holdsPerSecond = holdsPerSecond;
    this.holdsBurst = holdsBurst;
    this.ordersPerSecond = ordersPerSecond;
    this.ordersBurst = ordersBurst;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) return true;

    String path = request.getRequestURI();
    String bucket;
    double rate;
    int burst;

    if (path.endsWith("/holds")) {
      bucket = "holds";
      rate = holdsPerSecond;
      burst = holdsBurst;
    } else if (path.equals("/api/orders")) {
      bucket = "orders";
      rate = ordersPerSecond;
      burst = ordersBurst;
    } else {
      return true;
    }

    String identity = BuyerIdentity.require(request);
    RateLimiter.Decision decision = limiter.check(bucket, identity, rate, burst);
    if (decision.allowed()) return true;

    throw new ApiException(
        ErrorCode.RATE_LIMITED,
        "Too many requests. Try again in " + decision.retryAfterSeconds() + " seconds.",
        Map.of("bucket", bucket),
        decision.retryAfterSeconds());
  }
}
