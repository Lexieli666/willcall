package dev.willcall.ops;

import dev.willcall.realtime.EventStreamHub;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hooks that make failure modes reproducible from a test.
 *
 * <h2>Why these exist rather than mocking the client</h2>
 *
 * The protocol's most important property is that a client notices a message it never received.
 * Proving that needs a message to actually go missing. Faking it in the browser — stubbing
 * EventSource, dropping a frame in a test double — proves the test double works. Making the server
 * genuinely skip a sequence number proves the real client, over the real transport, through the
 * real proxy, notices and recovers.
 *
 * <h2>Why this is safe to have in the codebase</h2>
 *
 * The whole controller is behind {@code willcall.admin.test-hooks-enabled}, which defaults to
 * <b>false</b>. The local compose stack and CI set it; the Terraform does not, so a deployed
 * environment has no such endpoint mapped at all — not merely refused, but absent.
 */
@RestController
@RequestMapping("/api/admin/test")
@ConditionalOnProperty(name = "willcall.admin.test-hooks-enabled", havingValue = "true")
public class TestHooksController {

  private static final Logger log = LoggerFactory.getLogger(TestHooksController.class);

  private final JdbcTemplate jdbc;
  private final EventStreamHub hub;
  private final dev.willcall.reservation.service.OutboxScheduler outboxScheduler;

  public TestHooksController(
      JdbcTemplate jdbc,
      EventStreamHub hub,
      dev.willcall.reservation.service.OutboxScheduler outboxScheduler) {
    this.jdbc = jdbc;
    this.hub = hub;
    this.outboxScheduler = outboxScheduler;
    log.warn(
        "test hooks are enabled: /api/admin/test/** can inject gaps and force resyncs."
            + " This must never be true in a deployed environment.");
  }

  /**
   * Burns a sequence number without publishing anything.
   *
   * <p>The next real change therefore arrives with a number one higher than the client expects,
   * which is exactly what a lost pub/sub message looks like from the client's side. If gap
   * detection is broken, the client applies the delta and its map is quietly wrong from then on.
   */
  @PostMapping("/events/{eventId}/inject-gap")
  public Map<String, Object> injectGap(@PathVariable UUID eventId) {
    Long sequence =
        jdbc.queryForObject(
            "update events set last_sequence = last_sequence + 1 where id = ? returning last_sequence",
            Long.class,
            eventId);
    log.warn("injected a sequence gap for event {}; the sequence is now {}", eventId, sequence);
    return Map.of(
        "eventId", eventId.toString(), "burnedSequence", sequence == null ? -1 : sequence);
  }

  /** Tells every connected client for an event to start over. */
  @PostMapping("/events/{eventId}/force-resync")
  public Map<String, Object> forceResync(@PathVariable UUID eventId) {
    int connections = hub.connectionsFor(eventId);
    hub.closeAll("forced_by_test_hook");
    return Map.of("eventId", eventId.toString(), "connectionsClosed", connections);
  }

  /**
   * Publishes everything sitting in the outbox.
   *
   * <p>The relay's scheduler is switched off in the test profile so a test never has to guess
   * whether a background tick has happened yet. This is how a test says "now".
   */
  @PostMapping("/drain-outbox")
  public Map<String, Object> drainOutbox() {
    int published = outboxScheduler.drain();
    hub.flushNow();
    return Map.of("published", published);
  }

  /** Flushes the coalescer immediately, so a test need not sleep for the window. */
  @PostMapping("/flush-stream")
  public Map<String, Object> flushStream() {
    hub.flushNow();
    return Map.of("flushed", true, "openConnections", hub.openConnectionCount());
  }

  /** Expires every active hold now, so a test need not wait out a TTL. */
  @PostMapping("/expire-holds")
  public Map<String, Object> expireHolds() {
    int holds =
        jdbc.update(
            "update holds set expires_at = now() - interval '1 second' where status = 'ACTIVE'");
    jdbc.update(
        "update hold_groups set expires_at = now() - interval '1 second' where status = 'ACTIVE'");
    return Map.of("holdsMarkedExpired", holds);
  }
}
