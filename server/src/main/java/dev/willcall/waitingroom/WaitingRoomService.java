package dev.willcall.waitingroom;

import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.store.EventRepository;
import dev.willcall.catalog.store.SeatRepository;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * The fair queue.
 *
 * <h2>What "fair" means here, precisely</h2>
 *
 * Arrival order, recorded once at the server, paced by a token bucket whose rate is the measured
 * capacity of the reservation core. Not strict FIFO — admission happens in batches across replicas,
 * so two buyers a millisecond apart can swap. The inversion rate is measured and published rather
 * than being claimed away; see {@code docs/adr/0010-fairness-policy.md}.
 *
 * <h2>Behaviour when Redis is gone</h2>
 *
 * Every method here degrades to {@link QueuePosition.State#DEGRADED_OPEN}: the queue cannot be
 * consulted, so admission is open. Refusing to sell tickets because the *queue* is down would be a
 * worse outcome than selling them unpaced for a few minutes, and it is the only place where losing
 * Redis is visible to buyers.
 */
@Service
public class WaitingRoomService {

  private static final Logger log = LoggerFactory.getLogger(WaitingRoomService.class);

  private final StringRedisTemplate redis;
  private final EventRepository events;
  private final SeatRepository seats;
  private final AdmissionTokenService tokens;
  private final AdmissionRepository admissions;
  private final Clock clock;

  private final RedisScript<List<Object>> joinScript;
  private final RedisScript<List<Object>> admitScript;

  private final double defaultRatePerSecond;
  private final int burst;
  private final int maxBatch;
  private final Duration queueTtl;

  private final Counter joins;
  private final Counter rejoins;
  private final Counter admitted;
  private final Counter leaves;
  private final Counter degradedCalls;

  @SuppressWarnings("unchecked")
  public WaitingRoomService(
      StringRedisTemplate redis,
      EventRepository events,
      SeatRepository seats,
      AdmissionTokenService tokens,
      AdmissionRepository admissions,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${willcall.waitingroom.default-rate-per-second:200}") double defaultRatePerSecond,
      @Value("${willcall.waitingroom.burst:100}") int burst,
      @Value("${willcall.waitingroom.max-batch:500}") int maxBatch,
      @Value("${willcall.waitingroom.queue-ttl:PT6H}") Duration queueTtl) {
    this.redis = redis;
    this.events = events;
    this.seats = seats;
    this.tokens = tokens;
    this.admissions = admissions;
    this.clock = clock;
    this.defaultRatePerSecond = defaultRatePerSecond;
    this.burst = burst;
    this.maxBatch = maxBatch;
    this.queueTtl = queueTtl;

    this.joinScript = (RedisScript<List<Object>>) (RedisScript<?>) script("redis/join.lua");
    this.admitScript = (RedisScript<List<Object>>) (RedisScript<?>) script("redis/admit.lua");

    this.joins = Counter.builder("willcall.queue.joins").register(meterRegistry);
    this.rejoins =
        Counter.builder("willcall.queue.rejoins")
            .description("Joins from somebody already in the queue; their position is unchanged")
            .register(meterRegistry);
    this.admitted = Counter.builder("willcall.queue.admitted").register(meterRegistry);
    this.leaves = Counter.builder("willcall.queue.leaves").register(meterRegistry);
    this.degradedCalls =
        Counter.builder("willcall.queue.degraded")
            .description("Queue calls that fell through to open admission because Redis was gone")
            .register(meterRegistry);
  }

  private static RedisScript<?> script(String path) {
    DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
    redisScript.setLocation(new ClassPathResource(path));
    redisScript.setResultType(List.class);
    return redisScript;
  }

  private String queueKey(UUID eventId) {
    return "willcall:queue:" + eventId;
  }

  private String bucketKey(UUID eventId) {
    return "willcall:queue:bucket:" + eventId;
  }

  private String admittedKey(UUID eventId) {
    return "willcall:queue:admitted:" + eventId;
  }

  /** The measured admission rate for this event, or the service default if none is recorded. */
  public double rateFor(Event event) {
    return event.admissionRatePerSecond() == null
        ? defaultRatePerSecond
        : event.admissionRatePerSecond();
  }

  /**
   * Puts a buyer in the queue, or tells them where they already are.
   *
   * <p>Joining twice is not an error and does not move anybody. A phone that double-taps, a tab
   * that reloads, and a reconnect after a tunnel all arrive here, and all three must produce the
   * same answer.
   */
  public QueuePosition join(UUID eventId, String userRef) {
    Event event =
        events.find(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
    if (!event.waitingRoomEnabled()) {
      return new QueuePosition(
          QueuePosition.State.DEGRADED_OPEN,
          0,
          0,
          0L,
          false,
          clock.instant(),
          issue(event, userRef));
    }

    Instant now = clock.instant();
    try {
      List<Object> result =
          redis.execute(
              joinScript,
              List.of(queueKey(eventId), admittedKey(eventId)),
              userRef,
              Long.toString(now.toEpochMilli()),
              Long.toString(queueTtl.toMillis()));

      if (result == null || result.isEmpty()) return degraded(event, userRef);

      String state = asString(result.get(0));
      if ("admitted".equals(state)) {
        return admittedPosition(event, userRef, now);
      }

      long score = (long) Double.parseDouble(asString(result.get(1)));
      long rank = asLong(result.get(2));
      long queueLength = asLong(result.get(3));

      if ("joined".equals(state)) joins.increment();
      else rejoins.increment();

      return waiting(event, rank, queueLength, Instant.ofEpochMilli(score));
    } catch (RuntimeException e) {
      return degraded(event, userRef, e);
    }
  }

  /** Where a buyer stands right now, without changing anything. */
  public QueuePosition position(UUID eventId, String userRef) {
    Event event =
        events.find(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
    if (!event.waitingRoomEnabled()) {
      return new QueuePosition(
          QueuePosition.State.DEGRADED_OPEN,
          0,
          0,
          0L,
          false,
          clock.instant(),
          issue(event, userRef));
    }

    try {
      Boolean isAdmitted = redis.opsForHash().hasKey(admittedKey(eventId), userRef);
      if (Boolean.TRUE.equals(isAdmitted)) {
        return admittedPosition(event, userRef, clock.instant());
      }

      Long rank = redis.opsForZSet().rank(queueKey(eventId), userRef);
      if (rank == null) {
        return new QueuePosition(
            QueuePosition.State.NOT_QUEUED, 0, queueLength(eventId), null, false, null, null);
      }

      Double score = redis.opsForZSet().score(queueKey(eventId), userRef);
      return waiting(
          event,
          rank,
          queueLength(eventId),
          score == null ? clock.instant() : Instant.ofEpochMilli(score.longValue()));
    } catch (RuntimeException e) {
      return degraded(event, userRef, e);
    }
  }

  /** Gives up a place. Idempotent: leaving twice is the same as leaving once. */
  public void leave(UUID eventId, String userRef) {
    try {
      Long removed = redis.opsForZSet().remove(queueKey(eventId), userRef);
      if (removed != null && removed > 0) leaves.increment();
    } catch (RuntimeException e) {
      log.warn("could not remove {} from the queue for {}", userRef, eventId, e);
    }
  }

  public long queueLength(UUID eventId) {
    try {
      Long size = redis.opsForZSet().size(queueKey(eventId));
      return size == null ? 0 : size;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  public long admittedCount(UUID eventId) {
    try {
      Long size = redis.opsForHash().size(admittedKey(eventId));
      return size == null ? 0 : size;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /**
   * Admits as many as the bucket allows, atomically.
   *
   * <p>Every replica calls this on a timer and they share one bucket, so the combined admission
   * rate is the configured rate however many replicas exist. That is what the Lua script buys: the
   * usual alternative is a leader election, which adds a dependency and a failure mode where the
   * leader is dead but not yet noticed.
   *
   * @return the buyers admitted by this call
   */
  public List<String> admitBatch(Event event) {
    if (!event.waitingRoomEnabled()) return List.of();

    Instant now = clock.instant();
    try {
      List<Object> popped =
          redis.execute(
              admitScript,
              List.of(queueKey(event.id()), bucketKey(event.id()), admittedKey(event.id())),
              Long.toString(now.toEpochMilli()),
              Double.toString(rateFor(event)),
              Integer.toString(burst),
              Long.toString(queueTtl.toMillis()),
              Integer.toString(maxBatch));

      if (popped == null || popped.isEmpty()) return List.of();

      // ZPOPMIN returns member, score, member, score, ...
      List<String> users = new java.util.ArrayList<>(popped.size() / 2);
      List<AdmissionRepository.AdmissionRecord> records =
          new java.util.ArrayList<>(popped.size() / 2);
      for (int i = 0; i + 1 < popped.size(); i += 2) {
        String userRef = asString(popped.get(i));
        double score = Double.parseDouble(asString(popped.get(i + 1)));
        users.add(userRef);
        records.add(
            new AdmissionRepository.AdmissionRecord(
                event.id(), userRef, Instant.ofEpochMilli((long) score), now));
      }

      // The durable audit trail. Redis is the live queue and is allowed to disappear; the
      // published inversion rate has to rest on something that does not.
      admissions.recordAll(records);
      admitted.increment(users.size());
      return users;
    } catch (RuntimeException e) {
      log.warn(
          "admission pass failed for event {}; the queue is paused, not broken", event.id(), e);
      return List.of();
    }
  }

  private QueuePosition waiting(Event event, long rank, long queueLength, Instant joinedAt) {
    long position = rank + 1;
    double rate = rateFor(event);
    Long eta = rate > 0 ? (long) Math.ceil(position / rate) : null;

    // Somebody with more people ahead of them than there are seats left cannot be served. Saying
    // so costs nothing and is the difference between a queue and a waiting trap.
    int available = seats.countAvailable(event.id());
    boolean beyondInventory = rank >= available;

    return new QueuePosition(
        QueuePosition.State.WAITING, position, queueLength, eta, beyondInventory, joinedAt, null);
  }

  private QueuePosition admittedPosition(Event event, String userRef, Instant now) {
    return new QueuePosition(
        QueuePosition.State.ADMITTED,
        0,
        queueLength(event.id()),
        0L,
        false,
        now,
        issue(event, userRef));
  }

  private QueuePosition degraded(Event event, String userRef) {
    return degraded(event, userRef, null);
  }

  private QueuePosition degraded(Event event, String userRef, RuntimeException cause) {
    degradedCalls.increment();
    if (cause != null) {
      log.warn(
          "waiting room unavailable for event {}; admitting openly while it is down",
          event.id(),
          cause);
    }
    return new QueuePosition(
        QueuePosition.State.DEGRADED_OPEN, 0, 0, 0L, false, clock.instant(), issue(event, userRef));
  }

  private String issue(Event event, String userRef) {
    return tokens.issue(event.id().toString(), userRef);
  }

  /** A fresh admission token for somebody already through the queue. */
  public String tokenFor(Event event, String userRef) {
    return issue(event, userRef);
  }

  public int availableSeats(UUID eventId) {
    return seats.countAvailable(eventId);
  }

  private static String asString(Object value) {
    if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
    return String.valueOf(value);
  }

  private static long asLong(Object value) {
    if (value instanceof Number number) return number.longValue();
    return Long.parseLong(asString(value));
  }

  /** Every event with somebody waiting, for the admitter to work through. */
  public List<Event> eventsWithQueues() {
    return events.findAllOnSale().stream().filter(Event::waitingRoomEnabled).toList();
  }

  public Optional<Event> findEvent(UUID eventId) {
    return events.find(eventId);
  }
}
