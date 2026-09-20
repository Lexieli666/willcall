package dev.willcall.waitingroom;

import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.store.EventRepository;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Refuses a hold from somebody who has not been admitted.
 *
 * <h2>Why the check is a signature, not a lookup</h2>
 *
 * Verifying the token locally keeps Redis off the reservation path. A lookup would put the queue's
 * availability in front of the ability to sell a seat, which is precisely the coupling {@code
 * docs/adr/0001-postgresql-owns-correctness-redis-only-accelerates.md} exists to prevent.
 *
 * <h2>Why 403 and not 401</h2>
 *
 * The buyer is not unauthenticated; there is no login. They are identified and simply not yet
 * allowed through. 403 with a code of {@code not_admitted} says that, and the client knows to show
 * the queue rather than a sign-in prompt.
 */
@Component
public class AdmissionGuard {

  public static final String HEADER = "X-Willcall-Admission";

  private final EventRepository events;
  private final AdmissionTokenService tokens;
  private final Counter refused;
  private final Counter allowed;
  private final Counter notRequired;

  public AdmissionGuard(
      EventRepository events, AdmissionTokenService tokens, MeterRegistry meterRegistry) {
    this.events = events;
    this.tokens = tokens;
    this.refused = Counter.builder("willcall.admission.refused").register(meterRegistry);
    this.allowed = Counter.builder("willcall.admission.allowed").register(meterRegistry);
    this.notRequired =
        Counter.builder("willcall.admission.not_required")
            .description("Holds on events with no waiting room")
            .register(meterRegistry);
  }

  /** Throws {@link ApiException} with {@code not_admitted} when the buyer may not proceed. */
  public void require(UUID eventId, String userRef, HttpServletRequest request) {
    Event event =
        events.find(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
    if (!event.waitingRoomEnabled()) {
      notRequired.increment();
      return;
    }

    String token = request.getHeader(HEADER);
    if (tokens.verify(token, eventId.toString(), userRef).isEmpty()) {
      refused.increment();
      throw new ApiException(
          ErrorCode.NOT_ADMITTED,
          "Join the waiting room and wait to be admitted before holding seats",
          Map.of("queueUrl", "/api/events/" + eventId + "/queue/join"));
    }
    allowed.increment();
  }
}
