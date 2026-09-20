package dev.willcall.platform.system;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The status the browser shows. It duplicates {@code /ready} on purpose: infrastructure probes and
 * the user-facing panel have different consumers, and folding them together means a UI change can
 * take a replica out of the load balancer.
 */
@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

  private static final Logger log = LoggerFactory.getLogger(SystemStatusController.class);

  private final DataSource dataSource;
  private final RedisConnectionFactory redisConnectionFactory;
  private final ObjectProvider<BuildProperties> buildProperties;
  private final String replicaId;

  public SystemStatusController(
      DataSource dataSource,
      RedisConnectionFactory redisConnectionFactory,
      ObjectProvider<BuildProperties> buildProperties,
      org.springframework.core.env.Environment env) {
    this.dataSource = dataSource;
    this.redisConnectionFactory = redisConnectionFactory;
    this.buildProperties = buildProperties;
    this.replicaId = env.getProperty("willcall.replica-id", "local");
  }

  @GetMapping("/status")
  public Map<String, String> status() {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("status", "UP");
    body.put("postgres", probe(this::pingDatabase) ? "UP" : "DOWN");
    body.put("redis", probe(this::pingRedis) ? "UP" : "DEGRADED");
    body.put("replica", replicaId);
    BuildProperties build = buildProperties.getIfAvailable();
    body.put("version", build == null ? "dev" : build.getVersion());
    body.put("commit", shortCommit());
    return body;
  }

  /** The image is built with the commit baked in; "unknown" means somebody ran it by hand. */
  private static String shortCommit() {
    String commit = Optional.ofNullable(System.getenv("WILLCALL_GIT_COMMIT")).orElse("unknown");
    return commit.substring(0, Math.min(7, commit.length()));
  }

  private boolean probe(Probe probe) {
    try {
      return probe.run();
    } catch (Exception e) {
      log.warn("status probe failed: {}", e.toString());
      return false;
    }
  }

  private boolean pingDatabase() throws Exception {
    try (var conn = dataSource.getConnection();
        var st = conn.createStatement()) {
      return st.execute("select 1");
    }
  }

  private boolean pingRedis() {
    try (var conn = redisConnectionFactory.getConnection()) {
      return "PONG".equalsIgnoreCase(conn.ping());
    }
  }

  @FunctionalInterface
  private interface Probe {
    boolean run() throws Exception;
  }
}
