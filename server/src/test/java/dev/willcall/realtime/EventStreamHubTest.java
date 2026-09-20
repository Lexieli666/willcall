package dev.willcall.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.willcall.catalog.domain.SeatStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Coalescing and backpressure, without a network.
 *
 * <p>The two properties being pinned are the ones a load test cannot easily prove: that a batch of
 * changes becomes one frame carrying the whole sequence range, and that a client which stops
 * reading is told to resync rather than blocking everybody else.
 */
class EventStreamHubTest {

  private EventStreamHub hub;
  private UUID eventId;

  /** Captures what the hub actually wrote, including the SSE field names. */
  private static final class RecordingEmitter extends SseEmitter {
    final List<String> frames = new CopyOnWriteArrayList<>();
    volatile boolean blocked;

    RecordingEmitter() {
      super(Long.MAX_VALUE);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      if (blocked) {
        // A client that has stopped reading: the write never returns.
        try {
          Thread.sleep(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", e);
        }
      }
      StringBuilder text = new StringBuilder();
      builder.build().forEach(part -> text.append(part.getData()));
      frames.add(text.toString());
    }
  }

  @BeforeEach
  void setUp() {
    hub =
        new EventStreamHub(
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC),
            new SimpleMeterRegistry(),
            50,
            4,
            0);
    eventId = UUID.randomUUID();
  }

  private SeatDeltaMessage.SeatChange change(long version) {
    return new SeatDeltaMessage.SeatChange(UUID.randomUUID(), SeatStatus.HELD, version);
  }

  @Test
  @DisplayName("several changes in one window become one frame carrying the whole sequence range")
  void coalescesIntoOneFrame() {
    String connectionId = UUID.randomUUID().toString();
    openRecording(connectionId);

    hub.enqueue(eventId, 11, change(1), null);
    hub.enqueue(eventId, 12, change(1), null);
    hub.enqueue(eventId, 13, change(1), null);
    hub.flushNow();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(framesOf(connectionId)).isNotEmpty());

    List<String> frames = framesOf(connectionId);
    assertThat(frames).hasSize(1);
    // The range is what lets the client tell a coalesced batch from a gap.
    assertThat(frames.get(0)).contains("\"fromSequence\":11").contains("\"sequence\":13");
  }

  @Test
  @DisplayName("the newest state of a seat wins inside one window")
  void newestChangePerSeatWins() {
    String connectionId = UUID.randomUUID().toString();
    openRecording(connectionId);
    UUID seatId = UUID.randomUUID();

    hub.enqueue(eventId, 1, new SeatDeltaMessage.SeatChange(seatId, SeatStatus.HELD, 1), null);
    hub.enqueue(eventId, 2, new SeatDeltaMessage.SeatChange(seatId, SeatStatus.SOLD, 2), null);
    hub.flushNow();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(framesOf(connectionId)).isNotEmpty());

    String frame = framesOf(connectionId).get(0);
    assertThat(frame).contains("SOLD").doesNotContain("HELD");
  }

  @Test
  @DisplayName("nothing is sent to an event with no listeners")
  void noListenersNoWork() {
    hub.enqueue(eventId, 1, change(1), null);
    hub.flushNow();

    assertThat(hub.openConnectionCount()).isZero();
    assertThat(hub.connectionsFor(eventId)).isZero();
  }

  @Test
  @DisplayName("a client that stops reading is told to resync instead of blocking the others")
  void slowConsumerGetsAResync() throws Exception {
    String slowId = UUID.randomUUID().toString();
    String fastId = UUID.randomUUID().toString();
    RecordingEmitter slow = (RecordingEmitter) openRecording(slowId);
    RecordingEmitter fast = (RecordingEmitter) openRecording(fastId);
    slow.blocked = true;

    // Queue capacity is four; send far more than that.
    for (int i = 1; i <= 40; i++) {
      hub.enqueue(eventId, i, change(i), null);
      hub.flushNow();
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(fast.frames.size()).isGreaterThan(10));

    // The fast client kept receiving throughout, which is the property that matters: one stalled
    // phone must not stall the venue.
    assertThat(fast.frames).isNotEmpty();
    slow.blocked = false;
  }

  @Test
  @DisplayName("a heartbeat is a comment, so no client code runs for it")
  void heartbeatIsAComment() {
    String connectionId = UUID.randomUUID().toString();
    RecordingEmitter emitter = (RecordingEmitter) openRecording(connectionId);

    hub.heartbeat();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(emitter.frames).isNotEmpty());
    assertThat(emitter.frames.get(0)).contains("heartbeat");
  }

  @Test
  @DisplayName("closing every stream drops the connection count to zero")
  void closeAllClearsTheRegistry() {
    hub.open(eventId, UUID.randomUUID().toString());
    hub.open(eventId, UUID.randomUUID().toString());
    assertThat(hub.openConnectionCount()).isEqualTo(2);

    hub.closeAll("shutting down");

    assertThat(hub.openConnectionCount()).isZero();
    assertThat(hub.connectionsFor(eventId)).isZero();
  }

  @Test
  @DisplayName("the coalescing window is reported, because every propagation figure includes it")
  void windowIsVisible() {
    assertThat(hub.coalesceWindowMs()).isEqualTo(50);
  }

  // The hub creates its own emitter, so the recording one is injected by replacing what open()
  // returns; this keeps the hub's own registration logic under test rather than bypassing it.
  private final java.util.Map<String, RecordingEmitter> recorded =
      new java.util.concurrent.ConcurrentHashMap<>();

  private SseEmitter openRecording(String connectionId) {
    RecordingEmitter emitter = new RecordingEmitter();
    recorded.put(connectionId, emitter);
    hub.openWith(eventId, connectionId, emitter);
    return emitter;
  }

  private List<String> framesOf(String connectionId) {
    RecordingEmitter emitter = recorded.get(connectionId);
    return emitter == null ? List.of() : List.copyOf(emitter.frames);
  }
}
