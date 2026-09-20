package dev.willcall.platform.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

  private final RateLimitInterceptor rateLimitInterceptor;

  public RateLimitWebConfig(RateLimitInterceptor rateLimitInterceptor) {
    this.rateLimitInterceptor = rateLimitInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // Ordered before the admission check: a buyer hammering the endpoint should be told to slow
    // down rather than have their token verified four hundred times a second.
    registry
        .addInterceptor(rateLimitInterceptor)
        .order(0)
        .addPathPatterns("/api/events/*/holds", "/api/orders");
  }
}
