package dev.willcall.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RealtimeConfig {

  /**
   * The pub/sub listener container.
   *
   * <p>Declared explicitly rather than relying on auto-configuration so its failure behaviour is
   * visible: {@code recovery-interval} governs how quickly a replica re-subscribes after Redis
   * comes back, and that interval is the length of the window in which clients on this replica miss
   * other replicas' changes and have to resync.
   */
  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setRecoveryInterval(2_000L);
    return container;
  }
}
