package dev.willcall.platform.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfig {

  /**
   * Injected everywhere rather than calling {@code Instant.now()} inline.
   *
   * <p>Hold expiry, the sweeper and the checkout grace are all time arithmetic, and a test that has
   * to sleep for two minutes to watch a hold expire is a test nobody runs. With a clock bean the
   * expiry tests advance time instead of waiting.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
