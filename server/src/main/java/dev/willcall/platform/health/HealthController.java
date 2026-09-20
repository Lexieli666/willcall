package dev.willcall.platform.health;

import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and readiness endpoints at the paths the load balancer and docker-compose health checks
 * use. {@code /health} answers "this JVM is running"; {@code /ready} answers "this replica can
 * serve traffic", which means PostgreSQL is reachable. Redis being down degrades the waiting room
 * but must not take a replica out of rotation, so it is reported without failing readiness — see
 * docs/adr/0002-postgres-is-the-source-of-truth.md.
 */
@RestController
public class HealthController {

  private static final Logger log = LoggerFactory.getLogger(HealthController.class);

  private final DataSource dataSource;
  private final RedisConnectionFactory redisConnectionFactory;
  private final String replicaId;

  public HealthController(
      DataSource dataSource,
      RedisConnectionFactory redisConnectionFactory,
      org.springframework.core.env.Environment env) {
    this.dataSource = dataSource;
    this.redisConnectionFactory = redisConnectionFactory;
    this.replicaId = env.getProperty("willcall.replica-id", "local");
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("status", "UP", "replica", replicaId);
  }

  @GetMapping("/ready")
  public ResponseEntity<Map<String, Object>> ready() {
    boolean db = probeDatabase();
    boolean redis = probeRedis();
    Map<String, Object> body =
        Map.of(
            "status", db ? "UP" : "DOWN",
            "postgres", db ? "UP" : "DOWN",
            "redis", redis ? "UP" : "DEGRADED",
            "replica", replicaId);
    return db ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
  }

  private boolean probeDatabase() {
    try (var conn = dataSource.getConnection();
        var st = conn.createStatement()) {
      return st.execute("select 1");
    } catch (Exception e) {
      log.warn("readiness: postgres probe failed: {}", e.toString());
      return false;
    }
  }

  private boolean probeRedis() {
    try (var conn = redisConnectionFactory.getConnection()) {
      return "PONG".equalsIgnoreCase(conn.ping());
    } catch (Exception e) {
      log.warn(
          "readiness: redis probe failed (queue degrades, reservations unaffected): {}",
          e.toString());
      return false;
    }
  }
}
