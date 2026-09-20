package dev.willcall.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holds every open stream on this replica and fans seat changes out to them.
 *
 * <h2>Coalescing</h2>
 *
 * Changes accumulate per event and are flushed on a timer (50 ms by default). Under a flash sale
 * hundreds of seats change per second; one frame per change would spend more bytes on framing than
 * on content, and no person can see the difference.
 *
 * <p>Within a window, later changes to the same seat replace earlier ones — a seat that goes
 * AVAILABLE → HELD → SOLD inside one window is sent once, as SOLD. That is safe because the delta
 * carries the seat's version and the client keeps only the newest.
 *
 * <p><b>The window is inside every propagation number this project publishes.</b> A p99 of 180 ms
 * includes up to 50 ms of the server deliberately waiting. Quoting the figure without it would be a
 * discount the reader cannot see.
 *
 * <h2>Serialise once, send many</h2>
 *
 * A flush serialises each frame once and offers the same immutable {@link StreamMessage} to every
 * connection. At five thousand connections the alternative is five thousand identical encodings.
 */
@Component
public class EventStreamHub {

  private static final Logger log = LoggerFactory.getLogger(EventStreamHub.class);

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final long coalesceWindowMs;
  private final int queueCapacity;
  private final long streamTimeoutMs;

  /** event id -> open connections. */
  private final Map<UUID, Set<StreamConnection>> connectionsByEvent = new ConcurrentHashMap<>();

  /** event id -> seat changes waiting for the next flush, newest per seat. */
  private final Map<UUID, PendingBatch> pending = new ConcurrentHashMap<>();

  private final AtomicLong openConnections = new AtomicLong();
  private final Counter framesSent;
  private final Counter framesDropped;
  private final Counter resyncsSent;
  private final Timer flushTimer;

  public EventStreamHub(
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${willcall.realtime.coalesce-window-ms:50}") long coalesceWindowMs,
      @Value("${willcall.realtime.queue-capacity:64}") int queueCapacity,
      @Value("${willcall.realtime.stream-timeout-ms:0}") long streamTimeoutMs) {
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.coalesceWindowMs = coalesceWindowMs;
    this.queueCapacity = queueCapacity;
    this.streamTimeoutMs = streamTimeoutMs;

    this.framesSent = Counter.builder("willcall.sse.frames_sent").register(meterRegistry);
    this.framesDropped =
        Counter.builder("willcall.sse.frames_dropped")
            .description("Frames dropped because a connection's queue was full")
            .register(meterRegistry);
    this.resyncsSent = Counter.builder("willcall.sse.resyncs_sent").register(meterRegistry);
    this.flushTimer =
        Timer.builder("willcall.sse.flush_duration")
            .publishPercentiles(0.5, 0.99)
            .register(meterRegistry);
    Gauge.builder("willcall.sse.open_connections", openConnections, AtomicLong::doubleValue)
        .description("Streams currently open on this replica")
        .register(meterRegistry);
    Gauge.builder("willcall.sse.events_with_listeners", connectionsByEvent, Map::size)
        .register(meterRegistry);
  }

  private static final class PendingBatch {
    private final Map<UUID, SeatDeltaMessage.SeatChange> changes = new LinkedHashMap<>();

    /** Lowest sequence number consumed by this batch, so the client can verify contiguity. */
    private long fromSequence = Long.MAX_VALUE;

    private long sequence;
    private SeatDeltaMessage.SeatCounts counts;
  }

  public long coalesceWindowMs() {
    return coalesceWindowMs;
  }

  public long openConnectionCount() {
    return openConnections.get();
  }

  public int connectionsFor(UUID eventId) {
    Set<StreamConnection> set = connectionsByEvent.get(eventId);
    return set == null ? 0 : set.size();
  }

  /** Registers a new stream and returns the emitter the controller hands back to Spring. */
  public SseEmitter open(UUID eventId, String connectionId) {
    return open(eventId, connectionId, null);
  }

  /** As {@link #open(UUID, String)}, remembering which buyer the stream belongs to. */
  public SseEmitter open(UUID eventId, String connectionId, String userRef) {
    // No timeout by default: a stream is meant to live as long as the tab does, and Spring's
    // default of 30 s would make every client reconnect twice a minute.
    return openWith(
        eventId,
        connectionId,
        userRef,
        new SseEmitter(streamTimeoutMs <= 0 ? Long.MAX_VALUE : streamTimeoutMs));
  }

  /**
   * Registers a stream around an emitter the caller supplies.
   *
   * <p>Exists so a test can record what was actually written while still going through the hub's
   * real registration, coalescing and backpressure paths rather than around them.
   */
  public SseEmitter openWith(UUID eventId, String connectionId, SseEmitter emitter) {
    return openWith(eventId, connectionId, null, emitter);
  }

  public SseEmitter openWith(
      UUID eventId, String connectionId, String userRef, SseEmitter emitter) {
    StreamConnection connection =
        new StreamConnection(connectionId, eventId, userRef, emitter, queueCapacity);

    connectionsByEvent
        .computeIfAbsent(eventId, id -> ConcurrentHashMap.newKeySet())
        .add(connection);
    openConnections.incrementAndGet();

    Runnable cleanup =
        () -> {
          if (remove(connection)) {
            log.debug("stream {} closed after {} frames", connectionId, connection.sentCount());
          }
        };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(
        throwable -> {
          log.debug("stream {} errored: {}", connectionId, throwable.toString());
          cleanup.run();
        });

    connection.startWriter();
    return emitter;
  }

  private boolean remove(StreamConnection connection) {
    Set<StreamConnection> set = connectionsByEvent.get(connection.eventId());
    boolean removed = set != null && set.remove(connection);
    if (removed) {
      openConnections.decrementAndGet();
      connection.close();
      if (set.isEmpty()) connectionsByEvent.remove(connection.eventId(), set);
    }
    return removed;
  }

  /** Sends the opening snapshot directly, bypassing the coalescer. */
  public void sendSnapshot(UUID eventId, String connectionId, SeatSnapshotMessage snapshot) {
    StreamConnection connection = find(eventId, connectionId);
    if (connection == null) return;
    connection.offer(StreamMessage.snapshot(snapshot.sequence(), toJson(snapshot)));
  }

  /** Sends a catch-up delta directly, used when a reconnect can be served from history. */
  public void sendCatchUp(UUID eventId, String connectionId, SeatDeltaMessage delta) {
    StreamConnection connection = find(eventId, connectionId);
    if (connection == null) return;
    connection.offer(StreamMessage.delta(delta.sequence(), toJson(delta)));
  }

  public void sendResync(UUID eventId, String connectionId, long sequence, String reason) {
    StreamConnection connection = find(eventId, connectionId);
    if (connection == null) return;
    connection.offer(StreamMessage.resync(sequence, reason));
    resyncsSent.increment();
  }

  private StreamConnection find(UUID eventId, String connectionId) {
    Set<StreamConnection> set = connectionsByEvent.get(eventId);
    if (set == null) return null;
    for (StreamConnection connection : set) {
      if (connection.id().equals(connectionId)) return connection;
    }
    return null;
  }

  /**
   * Accepts a seat change for the next flush.
   *
   * <p>Called from the outbox relay for locally-produced changes and from the Redis subscriber for
   * changes made on other replicas. Both paths land here so there is one coalescer, not two.
   */
  public void enqueue(
      UUID eventId,
      long sequence,
      SeatDeltaMessage.SeatChange change,
      SeatDeltaMessage.SeatCounts counts) {
    if (connectionsByEvent.getOrDefault(eventId, Set.of()).isEmpty()) return;

    PendingBatch batch = pending.computeIfAbsent(eventId, id -> new PendingBatch());
    synchronized (batch) {
      // Newest wins within a window: a seat that went AVAILABLE -> HELD -> SOLD in 50 ms is sent
      // once, as SOLD. Safe because the client keeps the highest version it has seen.
      batch.changes.put(change.id(), change);
      batch.fromSequence = Math.min(batch.fromSequence, sequence);
      batch.sequence = Math.max(batch.sequence, sequence);
      if (counts != null) batch.counts = counts;
    }
  }

  @Scheduled(fixedDelayString = "${willcall.realtime.coalesce-window-ms:50}")
  public void flush() {
    if (pending.isEmpty()) return;
    flushTimer.record(this::flushNow);
  }

  /** Flushes every pending batch. Exposed so tests do not have to wait for the scheduler. */
  public void flushNow() {
    for (UUID eventId : List.copyOf(pending.keySet())) {
      PendingBatch batch = pending.remove(eventId);
      if (batch == null) continue;

      List<SeatDeltaMessage.SeatChange> changes;
      long fromSequence;
      long sequence;
      SeatDeltaMessage.SeatCounts counts;
      synchronized (batch) {
        if (batch.changes.isEmpty()) continue;
        changes = List.copyOf(batch.changes.values());
        fromSequence = batch.fromSequence;
        sequence = batch.sequence;
        counts = batch.counts;
      }

      Set<StreamConnection> listeners = connectionsByEvent.get(eventId);
      if (listeners == null || listeners.isEmpty()) continue;

      // One serialisation for every listener on this replica.
      StreamMessage frame =
          StreamMessage.delta(
              sequence, toJson(new SeatDeltaMessage(fromSequence, sequence, changes, counts)));

      for (StreamConnection connection : listeners) {
        if (connection.isClosed()) {
          remove(connection);
          continue;
        }
        if (connection.offer(frame)) {
          framesSent.increment();
        } else {
          framesDropped.increment();
          resyncsSent.increment();
        }
      }
    }
  }

  /**
   * An SSE comment every fifteen seconds.
   *
   * <p>A comment rather than an event, so {@code EventSource} ignores it and no client code runs.
   * It exists to stop proxies reaping an idle connection, and to give the client a liveness signal
   * that does not depend on TCP noticing a dead peer.
   */
  @Scheduled(fixedDelayString = "${willcall.realtime.heartbeat-ms:15000}")
  public void heartbeat() {
    if (connectionsByEvent.isEmpty()) return;
    StreamMessage beat = new StreamMessage(null, null, "heartbeat " + clock.instant());
    for (Set<StreamConnection> listeners : connectionsByEvent.values()) {
      for (StreamConnection connection : listeners) {
        if (connection.isClosed()) remove(connection);
        else connection.offer(beat);
      }
    }
  }

  /** Closes every stream; used on shutdown so clients reconnect rather than hanging. */
  public void closeAll(String reason) {
    for (Set<StreamConnection> listeners : connectionsByEvent.values()) {
      for (StreamConnection connection : listeners) {
        connection.offer(StreamMessage.resync(0, reason));
        connection.close();
      }
    }
    connectionsByEvent.clear();
    openConnections.set(0);
  }

  /**
   * Sends a personal frame to whichever connections belong to a buyer.
   *
   * <p>Queue position is per person, so it cannot ride the coalescer, which exists to send one
   * identical frame to everybody. A buyer with two tabs open gets it on both.
   *
   * @return how many connections received it
   */
  public int sendToUser(UUID eventId, String userRef, String eventName, String json) {
    Set<StreamConnection> listeners = connectionsByEvent.get(eventId);
    if (listeners == null || userRef == null) return 0;
    int sent = 0;
    for (StreamConnection connection : listeners) {
      if (!userRef.equals(connection.userRef())) continue;
      if (connection.isClosed()) {
        remove(connection);
        continue;
      }
      // No sequence id: queue frames are outside the seat-delta sequence entirely, so giving them
      // one would corrupt the client's cursor. See docs/realtime-protocol.md section 3.5.
      if (connection.offer(new StreamMessage(eventName, null, json))) {
        sent++;
        framesSent.increment();
      }
    }
    return sent;
  }

  /** The buyers with at least one open stream for an event, for the queue broadcaster. */
  public Set<String> connectedUsers(UUID eventId) {
    Set<StreamConnection> listeners = connectionsByEvent.get(eventId);
    if (listeners == null) return Set.of();
    Set<String> users = ConcurrentHashMap.newKeySet();
    for (StreamConnection connection : listeners) {
      String userRef = connection.userRef();
      if (userRef != null && !connection.isClosed()) users.add(userRef);
    }
    return users;
  }

  /** Per-connection queue depths, for the load report's memory and backpressure sections. */
  public Map<String, Integer> queueDepths() {
    Map<String, Integer> depths = new LinkedHashMap<>();
    for (Set<StreamConnection> listeners : connectionsByEvent.values()) {
      for (StreamConnection connection : listeners) {
        depths.put(connection.id(), connection.queueDepth());
      }
    }
    return depths;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stream payload is not serialisable", e);
    }
  }
}
