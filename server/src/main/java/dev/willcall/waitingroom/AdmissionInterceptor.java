package dev.willcall.waitingroom;

import dev.willcall.platform.web.BuyerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a hold from a buyer the queue has not admitted.
 *
 * <h2>Why an interceptor rather than a call from the controller</h2>
 *
 * The obvious version — {@code admissionGuard.require(...)} at the top of the hold handler — makes
 * {@code reservation} depend on {@code waitingroom}, which depends on {@code realtime}, which
 * depends on {@code reservation}. That is a cycle, and the architecture test refused it. An
 * interceptor registered by the waiting room lets the dependency run one way: the waiting room
 * knows about holds, and holds know nothing about the waiting room.
 *
 * <p>It also means the check happens before the controller runs, so a request that will be refused
 * never reaches the idempotency layer and never burns a key — otherwise a buyer who is turned away,
 * joins the queue and retries with the same key would be told their request had already been
 * processed.
 */
@Component
public class AdmissionInterceptor implements HandlerInterceptor {

  private static final Pattern HOLD_PATH =
      Pattern.compile("^/api/events/([0-9a-fA-F-]{36})/holds/?$");

  private final AdmissionGuard guard;

  public AdmissionInterceptor(AdmissionGuard guard) {
    this.guard = guard;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) return true;

    Matcher matcher = HOLD_PATH.matcher(request.getRequestURI());
    if (!matcher.matches()) return true;

    UUID eventId = UUID.fromString(matcher.group(1));
    // Both of these throw an ApiException, which the usual handler turns into problem+json. An
    // interceptor that wrote the response itself would produce a body in a different shape from
    // every other error the API returns.
    String userRef = BuyerIdentity.require(request);
    guard.require(eventId, userRef, request);
    return true;
  }
}
