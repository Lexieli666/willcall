package dev.willcall.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Carries seat deltas between replicas over Redis pub/sub.
 *
 * <h2>Fire and forget, on purpose</h2>
 *
 * Redis pub/sub has no persistence and no acknowledgement: a message published while a replica is
 * disconnected is lost to that replica, permanently. That is acceptable here and is why the
 * protocol has gap detection rather than treating it as a nicety. A client that misses a delta
 * notices the sequence gap and resyncs against PostgreSQL, which is the same recovery path it takes
 * after a network blip.
 *
 * <p>Choosing a durable bus instead — Streams, or Kafka — would buy at-least-once delivery of
 * something the client can already recover without, at the cost of a second stateful dependency
 * whose failure modes would then need their own game day.
 *
 * <h2>Behaviour when Redis is down</h2>
 *
 * Publishing failures are logged and swallowed. Each replica still delivers changes it made itself,
 * clients on other replicas detect the gap and resync, and the reservation core does not notice.
 * That is the contract in {@code
 * docs/adr/0001-postgresql-owns-correctness-redis-only-accelerates.md}, and {@code
 * RedisOutageInvariantIntegrationTest} is what enforces it.
 */
@Component
public class SeatDeltaBus {

  private static final Logger log = LoggerFactory.getLogger(SeatDeltaBus.class);
  private static final String CHANNEL_PREFIX = "willcall:event:";
  private static final String CHANNEL_PATTERN = CHANNEL_PREFIX + "*";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final EventStreamHub hub;
  private final String replicaId;

  private final Counter published;
  private final Counter publishFailures;
  private final Counter received;
  private final Counter receivedFromSelf;

  public SeatDeltaBus(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      EventStreamHub hub,
      RedisMessageListenerContainer listenerContainer,
      MeterRegistry meterRegistry,
      org.springframework.core.env.Environment environment) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.hub = hub;
    this.replicaId = environment.getProperty("willcall.replica-id", "local");

    this.published = Counter.builder("willcall.bus.published").register(meterRegistry);
    this.publishFailures =
        Counter.builder("willcall.bus.publish_failures")
            .description("Deltas that could not be published; clients recover by resyncing")
            .register(meterRegistry);
    this.received = Counter.builder("willcall.bus.received").register(meterRegistry);
    this.receivedFromSelf =
        Counter.builder("willcall.bus.received_from_self")
            .description("Messages this replica published and then read back")
            .register(meterRegistry);

    listenerContainer.addMessageListener(new Subscriber(), new PatternTopic(CHANNEL_PATTERN));
  }

  /**
   * What travels on the wire.
   *
   * @param origin the replica that produced it, so a replica can ignore its own echo
   */
  public record BusMessage(
      UUID eventId,
      long sequence,
      String origin,
      SeatDeltaMessage.SeatChange change,
      SeatDeltaMessage.SeatCounts counts) {}

  public void publish(
      UUID eventId,
      long sequence,
      SeatDeltaMessage.SeatChange change,
      SeatDeltaMessage.SeatCounts counts) {
    // Deliver locally first and unconditionally. If Redis is unreachable, the clients on this
    // replica must still see the change they just caused.
    hub.enqueue(eventId, sequence, change, counts);

    try {
      String payload =
          objectMapper.writeValueAsString(
              new BusMessage(eventId, sequence, replicaId, change, counts));
      redis.convertAndSend(CHANNEL_PREFIX + eventId, payload);
      published.increment();
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("bus payload is not serialisable", e);
    } catch (RuntimeException e) {
      publishFailures.increment();
      log.warn(
          "could not publish delta for event {} sequence {}; clients on other replicas will resync",
          eventId,
          sequence,
          e);
    }
  }

  private final class Subscriber implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
      try {
        BusMessage busMessage =
            objectMapper.readValue(
                new String(message.getBody(), StandardCharsets.UTF_8), BusMessage.class);
        received.increment();

        if (replicaId.equals(busMessage.origin())) {
          // Already delivered locally by publish(). Enqueuing it again would be harmless — the
          // coalescer keys on seat id and the client is version-guarded — but counting it makes
          // the fan-out metrics honest.
          receivedFromSelf.increment();
          return;
        }

        hub.enqueue(
            busMessage.eventId(), busMessage.sequence(), busMessage.change(), busMessage.counts());
      } catch (Exception e) {
        log.warn("could not handle a bus message; the affected clients will resync", e);
      }
    }
  }
}
