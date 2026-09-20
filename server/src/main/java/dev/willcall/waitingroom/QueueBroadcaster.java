package dev.willcall.waitingroom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.willcall.catalog.domain.Event;
import dev.willcall.realtime.EventStreamHub;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes each waiting buyer their own position, over the stream they already have open.
 *
 * <h2>Why a push and not a poll</h2>
 *
 * Ten thousand people polling their position every two seconds is 5,000 requests per second of pure
 * overhead, competing with the hold path for the same connection pool at exactly the moment the
 * hold path is busiest. The stream is already open; the position rides on it.
 *
 * <h2>One Redis read per event, not one per buyer</h2>
 *
 * The obvious implementation asks Redis for each connected buyer's rank, which is one round trip
 * per person per tick — at five thousand connections, 2,500 per second. Instead the whole queue is
 * read once per event per tick and ranks are computed locally. That is one round trip regardless of
 * how many people are watching, at the cost of transferring the queue, which is small compared with
 * the alternative.
 *
 * <h2>Admission arrives here too</h2>
 *
 * A buyer admitted by one replica may be connected to another, so admission is not pushed by
 * whoever admitted them. Each replica checks its own connections against the admitted set on the
 * same tick, which handles the cross-replica case without any extra plumbing.
 */
@Component
public class QueueBroadcaster {

  private static final Logger log = LoggerFactory.getLogger(QueueBroadcaster.class);
  public static final String EVENT_NAME = "queue";

  private final WaitingRoomService waitingRoom;
  private final EventStreamHub hub;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Counter framesSent;

  public QueueBroadcaster(
      WaitingRoomService waitingRoom,
      EventStreamHub hub,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${willcall.waitingroom.broadcast-enabled:true}") boolean enabled) {
    this.waitingRoom = waitingRoom;
    this.hub = hub;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.framesSent = Counter.builder("willcall.queue.position_frames").register(meterRegistry);
  }

  /** What a waiting or newly-admitted buyer receives on the stream. */
  public record QueueFrame(
      String state,
      long position,
      long queueLength,
      Long estimatedWaitSeconds,
      boolean beyondInventory,
      String admissionToken,
      Instant serverTime) {}

  @Scheduled(fixedDelayString = "${willcall.waitingroom.broadcast-interval-ms:2000}")
  public void tick() {
    if (!enabled) return;
    if (!running.compareAndSet(false, true)) return;
    try {
      broadcast();
    } catch (RuntimeException e) {
      log.warn("queue broadcast failed; retrying on the next tick", e);
    } finally {
      running.set(false);
    }
  }

  /** One pass. Exposed so a test does not have to wait two seconds. */
  public int broadcast() {
    int sent = 0;
    Instant now = Instant.now();

    for (Event event : waitingRoom.eventsWithQueues()) {
      Set<String> watchers = hub.connectedUsers(event.id());
      if (watchers.isEmpty()) continue;

      List<String> queue;
      Map<Object, Object> admitted;
      try {
        // One read of the whole queue, then ranks computed locally.
        Set<String> ordered = redis.opsForZSet().range("willcall:queue:" + event.id(), 0, -1);
        queue = ordered == null ? List.of() : List.copyOf(ordered);
        admitted = redis.opsForHash().entries("willcall:queue:admitted:" + event.id());
      } catch (RuntimeException e) {
        // Redis is gone. The queue degrades to open admission, which the buyer finds out from
        // their next request; broadcasting a wrong position would be worse than broadcasting none.
        log.debug("skipping queue broadcast for {}: {}", event.id(), e.toString());
        continue;
      }

      double rate = waitingRoom.rateFor(event);
      int available = -1;

      for (int index = 0; index < queue.size(); index++) {
        String userRef = queue.get(index);
        if (!watchers.contains(userRef)) continue;

        if (available < 0) available = waitingRoom.availableSeats(event.id());
        long position = index + 1L;
        QueueFrame frame =
            new QueueFrame(
                "WAITING",
                position,
                queue.size(),
                rate > 0 ? (long) Math.ceil(position / rate) : null,
                index >= available,
                null,
                now);
        sent += hub.sendToUser(event.id(), userRef, EVENT_NAME, toJson(frame));
      }

      for (Object key : admitted.keySet()) {
        String userRef = String.valueOf(key);
        if (!watchers.contains(userRef)) continue;
        QueueFrame frame =
            new QueueFrame(
                "ADMITTED", 0, queue.size(), 0L, false, waitingRoom.tokenFor(event, userRef), now);
        sent += hub.sendToUser(event.id(), userRef, EVENT_NAME, toJson(frame));
      }
    }

    framesSent.increment(sent);
    return sent;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("queue frame is not serialisable", e);
    }
  }
}
