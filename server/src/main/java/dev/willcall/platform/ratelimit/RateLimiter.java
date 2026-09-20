package dev.willcall.platform.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-identity token bucket, shared across replicas through Redis.
 *
 * <h2>Fails open</h2>
 *
 * If Redis is unreachable the limiter allows the request. That is a deliberate choice and the
 * uncomfortable direction: a broken limiter lets abuse through, where failing closed would refuse
 * every legitimate buyer. Refusing to sell tickets because the *rate limiter* is down is a worse
 * outcome than being briefly unprotected, and it is consistent with
 * {@code docs/adr/0001-postgresql-owns-correctness-redis-only-accelerates.md}: Redis accelerates,
 * it does not gate. The number of times this happens is a counter with an alert on it.
 *
 * <h2>Why the decision is one round trip</h2>
 *
 * Read-decide-write lets a burst of concurrent requests all read the same count and all conclude
 * they are allowed — which is the exact case a limiter exists for. The refill and the decrement
 * happen inside one Lua script, so the decision is atomic across every replica.
 */
@Component
public class RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

  private final StringRedisTemplate redis;
  private final Clock clock;
  private final RedisScript<List<Object>> script;
  private final boolean enabled;
  private final Duration keyTtl;

  private final Counter allowed;
  private final Counter refused;
  private final Counter failedOpen;

  @SuppressWarnings("unchecked")
  public RateLimiter(
      StringRedisTemplate redis,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${willcall.ratelimit.enabled:true}") boolean enabled,
      @Value("${willcall.ratelimit.key-ttl:PT10M}") Duration keyTtl) {
    this.redis = redis;
    this.clock = clock;
    this.enabled = enabled;
    this.keyTtl = keyTtl;

    DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
    redisScript.setLocation(new ClassPathResource("redis/ratelimit.lua"));
    redisScript.setResultType(List.class);
    this.script = (RedisScript<List<Object>>) (RedisScript<?>) redisScript;

    this.allowed = Counter.builder("willcall.ratelimit.allowed").register(meterRegistry);
    this.refused = Counter.builder("willcall.ratelimit.refused").register(meterRegistry);
    this.failedOpen =
        Counter.builder("willcall.ratelimit.failed_open")
            .description("Decisions that allowed the request because Redis was unreachable")
            .register(meterRegistry);
  }

  /**
   * @param allowed whether the caller may proceed
   * @param retryAfterSeconds how long until a token exists, for the {@code Retry-After} header. A
   *     client told to retry in one second when it will be refused for four just produces three
   *     more refusals.
   */
  public record Decision(boolean allowed, int retryAfterSeconds, double tokensRemaining) {
    static Decision allow(double tokens) {
      return new Decision(true, 0, tokens);
    }
  }

  public Decision check(String bucket, String identity, double ratePerSecond, int burst) {
    if (!enabled) return Decision.allow(burst);

    String key = "willcall:ratelimit:" + bucket + ":" + identity;
    try {
      List<Object> result =
          redis.execute(
              script,
              List.of(key),
              Long.toString(clock.millis()),
              Double.toString(ratePerSecond),
              Integer.toString(burst),
              Long.toString(keyTtl.toMillis()));

      if (result == null || result.size() < 3) {
        failedOpen.increment();
        return Decision.allow(burst);
      }

      boolean ok = asLong(result.get(0)) == 1;
      double tokens = Double.parseDouble(String.valueOf(result.get(1)));
      long retryAfterMillis = asLong(result.get(2));

      if (ok) {
        allowed.increment();
        return new Decision(true, 0, tokens);
      }
      refused.increment();
      // Rounded up, and never zero: Retry-After: 0 invites an immediate retry, which is the
      // behaviour the limiter is trying to stop.
      return new Decision(false, Math.max(1, (int) Math.ceil(retryAfterMillis / 1000.0)), tokens);
    } catch (RuntimeException e) {
      // Fail open. Being briefly unprotected is better than refusing every legitimate buyer
      // because the limiter's data store is down.
      failedOpen.increment();
      log.warn("rate limiter unavailable; allowing the request", e);
      return Decision.allow(burst);
    }
  }

  private static long asLong(Object value) {
    if (value instanceof Number number) return number.longValue();
    return Long.parseLong(String.valueOf(value));
  }
}
